// we need to be in this package to access package private classes
// like SourceFileEntryImpl and PackageEntryImpl
package dotty.tools.dotc.interactive

import dotty.tools.dotc.classpath.BinaryFileEntry
import dotty.tools.dotc.classpath.ClassPathEntries
import dotty.tools.dotc.classpath.PackageEntry
import dotty.tools.dotc.classpath.PackageEntryImpl
import dotty.tools.dotc.classpath.PackageName
import dotty.tools.dotc.classpath.SourceFileEntry
import dotty.tools.io.AbstractFile
import dotty.tools.io.ClassPath

import java.io.File
import java.net.URL
import java.nio.file.Path
import scala.collection.mutable

/**
 * A ClassPath implementation that can find sources regardless of the directory where they're declared.
 */
class LogicalSourcePath(val sourcepath: String, rootPackage: LogicalPackage)
    extends ClassPath {

  def findClassFile(className: String): Option[AbstractFile] = None
  override def classes(inPackage: PackageName): Seq[BinaryFileEntry] = Seq.empty

  override def hasPackage(inPackage: PackageName): Boolean = findPackage(
    inPackage.dottedString
  ).isDefined

  /** Return all packages contained inside `inPackage`. Package entries contain the *full name* of the package. */
  override def packages(inPackage: PackageName): Seq[PackageEntry] =
    val rawPackage = inPackage.dottedString
    findPackage(rawPackage) match
      case Some(pkg) => packagesIn(pkg, rawPackage)
      case None => Seq.empty[PackageEntry]
  

  /** Return all sources contained directly inside `inPackage` */
  override def sources(inPackage: PackageName): Seq[SourceFileEntry] =
    val rawPackage = inPackage.dottedString
    findPackage(rawPackage) match
      case Some(pkg) =>
        val res = sourcesIn(pkg)
        res
      case None => Seq.empty[SourceFileEntry]

  private def sourcesIn(pkg: LogicalPackage) =
    pkg.sources.map(p => SourceFileEntry(AbstractFile.getFile(p)))

  private def packagesIn(pkg: LogicalPackage, prefix: String) =
    val pre = if (prefix.isEmpty) prefix else s"$prefix."
    pkg.packages.map(p => PackageEntryImpl(pre + p.name))

  /** Allows to get entries for packages and classes merged with sources possibly in one pass. */
  override def list(inPackage: PackageName): ClassPathEntries =
    val rawPackage = inPackage.dottedString
    val res = findPackage(rawPackage) match
      case Some(pkg) =>
        ClassPathEntries(packagesIn(pkg, rawPackage), sourcesIn(pkg))
      case None => ClassPathEntries(Seq(), Seq())
    res


  /** Not sure what the purpose of this method really is, it's not called by the compiler */
  override def asURLs: Seq[URL] = sourcepath.split(File.pathSeparator).toIndexedSeq.map(new File(_)).map(_.toURI.toURL)

  override def asClassPathStrings: Seq[String] = Seq()

  override def asSourcePathString: String = sourcepath

  /** Return the package for the given fullName, if any */
  private def findPackage(fullName: String): Option[LogicalPackage] =
    if fullName == "" then Option(rootPackage)
    else
      fullName.split('.').foldLeft(Option(rootPackage)) { (pkg, name) =>
        pkg.flatMap(_.getPackage(name))
      }


  override def toString: String = rootPackage.prettyPrint()
}

/**
 * A logical package representation. This is disconnected from the file system, and faithfully
 * represents the nesting of packages and sources that contribute classes to those packages.
 */
trait LogicalPackage {

  def name: String

  def packages: Seq[LogicalPackage]

  /**
   * Return all sources contained by this package. Only direct members are returned, and there are no duplicates.
   *
   */
  def sources: Seq[String]

  def getPackage(name: String): Option[LogicalPackage]

  def prettyPrint(): String = {
    prettyPrintWith().toString().stripTrailing().stripIndent()
  }

  private def prettyPrintWith(
      indent: Int = 0,
      sb: StringBuilder = new StringBuilder
  ): StringBuilder =
    sb ++= " " * indent
    sb ++= s"$name\n"
    packages.sortBy(_.name).foreach(_.prettyPrintWith(indent + 4, sb))
    sources.foreach { s =>
      sb ++= (" " * (indent + 4))
      sb ++= Option(AbstractFile.getFile(s)).map(_.name).getOrElse(s) + "\n"
    }
    sb
  
}

/**
 * Represent a package and its contents in a way that's close to the file system.
 *
 * A package contains any number of nested packages and source files. It is mutable in order to allow adding
 * members at any level, as they are discovered by parsing source files.
 *
 * @param name simple name of the package
 * @note This class is not thread safe
 */
class ParsedLogicalPackage(
    val name: String,
    val parent: Option[ParsedLogicalPackage]
) extends LogicalPackage {
  require(
    (name.trim.isEmpty && parent.isEmpty) || (name.trim.nonEmpty && parent.nonEmpty),
    s"Unexpected package name `$name` and parent `$parent`."
  )

  def this(name: String, parent: ParsedLogicalPackage) =
    this(name, Some(parent))

  private val subpackages =
    mutable.LinkedHashMap.empty[String, ParsedLogicalPackage]
  private val directSources = mutable.ListBuffer.empty[String]

  def fullName: String =
    if (parent.isEmpty || parent.get.name.isEmpty) name
    else s"${parent.get.fullName}.$name"

  def removeEmptyPackages(): Unit =
    subpackages.values.foreach(_.removeEmptyPackages())
    if subpackages.isEmpty && directSources.isEmpty then
      parent.foreach(_.subpackages.remove(name))
    

  /**
   * Return the existing member package, or create a new one and add it to this package.
   */
  def enterPackage(name: String): ParsedLogicalPackage = synchronized:
    subpackages.get(name) match {
      case Some(p) => p
      case None =>
        val p = new ParsedLogicalPackage(name, this)
        subpackages(name) = p
        p
    }
  

  def getPackage(name: String): Option[ParsedLogicalPackage] =
    subpackages.get(name)

  def enterSource(fileName: String): this.type = synchronized:
    directSources += fileName
    this

  /** Return all member packages. Only direct members are returned. */
  def packages: Seq[ParsedLogicalPackage] = subpackages.values.toList

  /**
   * Return all sources contained by this package. Only direct members are returned, and there are no duplicates.
   *
   * The return type is a sequence and not a Set in order to have deterministic runs
   */
  def sources: Seq[String] = directSources.toSeq.distinct

  override def toString(): String =
    s"package $name(${packages.size} packages and ${sources.size} files)"
}

