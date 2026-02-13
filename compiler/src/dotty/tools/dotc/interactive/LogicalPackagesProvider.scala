package dotty.tools.dotc.interactive

import scala.language.unsafeNulls

import java.io.File
import scala.collection.mutable
import scala.util.control.NonFatal

import dotty.tools.io.AbstractFile
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.parsing.JavaParsers
import dotty.tools.dotc.util.SourceFile

/**
 * A compiler component that adds support for parsing Scala and Java source files and finding out
 * the logical package structure of the whole source path.
 * TODO! Add the LogicalPackagesProvider in interactive context, also enable sourcepath in the settings for it
 * TODO update mtags to 2.0.0-M7
 * This implementation works with Scala 3's parser and AST structures.
 */
object LogicalPackagesProvider {
  // Use a minimal context for parsing
  private given Context = (new ContextBase).initialCtx

  /**
   * Parse all source files in the sourcepath and build the logical package structure.
   *
   * @param sourcePath The sourcepath as a string (colon or semicolon separated)
   * @return The root LogicalPackage containing the entire package hierarchy
   */
  def parseSourcePath(sourcePath: String): LogicalPackage = {
    val sourceRoots = allSources(sourcePath)

    val root = new ParsedLogicalPackage("", None)

    for (sourceFile <- sourceRoots) {
      try {
        parseSourceFile(sourceFile, root)
      } catch {
        case NonFatal(e) =>
          // Silently ignore parsing errors
      }
    }

    root
  }

  /**
   * Parse a single source file and extract its package and class/object definitions.
   */
  private def parseSourceFile(
      sourceFile: SourceFile,
      rootPackage: ParsedLogicalPackage
  ): Unit = {
    val fileName = sourceFile.path

    if (fileName.endsWith(".scala")) {
      // For Scala files, use the OutlineParser
      parseScalaSourceFile(sourceFile, fileName, rootPackage)
    } else if (fileName.endsWith(".java")) {
      // For Java files, use OutlineJavaParser
      parseJavaSourceFile(sourceFile, fileName, rootPackage)
    }
  }

  /**
   * Parse a Scala source file to extract package and class definitions.
   */
  private def parseScalaSourceFile(
      sourceFile: SourceFile,
      fileName: String,
      rootPackage: ParsedLogicalPackage
  ): Unit = {
    try {
      // Use OutlineParser for fast parsing that skips method bodies
      val parser = new Parsers.OutlineParser(sourceFile)
      val tree = parser.parse()

      // Traverse the tree to extract package info
      val traverser = new SourceFileTraverser(fileName, rootPackage)
      traverser.traverse(tree)
    } catch {
      case NonFatal(e) =>
        // Silently ignore parsing errors
    }
  }

  /**
   * Parse a Java source file to extract package and class definitions.
   */
  private def parseJavaSourceFile(
      sourceFile: SourceFile,
      fileName: String,
      rootPackage: ParsedLogicalPackage
  ): Unit = {
    try {
      // Use OutlineJavaParser for fast parsing
      val parser = new JavaParsers.OutlineJavaParser(sourceFile)
      val tree = parser.parse()

      // Traverse the tree to extract package info
      val traverser = new SourceFileTraverser(fileName, rootPackage)
      traverser.traverse(tree)
    } catch {
      case NonFatal(e) =>
        // Silently ignore parsing errors
    }
  }

  /**
   * Traverse an untyped AST to extract package and class definitions.
   */
  private class SourceFileTraverser(
      fileName: String,
      rootPackage: ParsedLogicalPackage
  ) {
    private var currentPackage = rootPackage

    def traverse(tree: untpd.Tree): Unit = tree match {
      case untpd.Thicket(trees) =>
        trees.foreach(traverse)

      case pkg: untpd.PackageDef =>
        traversePackageDef(pkg)

      case mod: untpd.ModuleDef =>
        // Top-level class or object in default package
        currentPackage.enterSource(fileName)

      case _ =>
        // Ignore other trees at the top level
    }

    private def traversePackageDef(pkg: untpd.PackageDef): Unit = {
      // Navigate to the package
      val pkgName = packageNameFromTree(pkg.pid)
      val newCurrentPackage = {
        var current = rootPackage
        for (part <- pkgName.split('.') if part.nonEmpty) {
          current = current.enterPackage(part)
        }
        current
      }

      // Check if the package contains any class or object definitions
      val hasClassOrObject = pkg.stats.exists {
        case _: untpd.ModuleDef => true
        case _ => false
      }

      if (hasClassOrObject) {
        newCurrentPackage.enterSource(fileName)
      }

      // Traverse nested definitions
      val oldPackage = currentPackage
      currentPackage = newCurrentPackage
      pkg.stats.foreach(traverse)
      currentPackage = oldPackage
    }

    private def packageNameFromTree(tree: untpd.Tree): String = tree match {
      case untpd.Ident(name) =>
        if (name.toString == "_root_" || name.toString.isEmpty) ""
        else name.toString

      case untpd.Select(qual, name) =>
        val qualName = packageNameFromTree(qual)
        if (qualName.isEmpty) name.toString
        else s"$qualName.${name.toString}"

      case _ =>
        ""
    }
  }

  /**
   * Return all Scala and Java sources from the given sourcepath string.
   */
  private def allSources(srcPath: String): Seq[SourceFile] = {
    val entries = srcPath.split(File.pathSeparator).map(_.trim).filter(_.nonEmpty)
    val sourceDirs = mutable.ListBuffer.empty[AbstractFile]
    val sourceFiles = mutable.ListBuffer.empty[SourceFile]

    for (entry <- entries) {
      val file = AbstractFile.getDirectory(entry)
      if (file != null) {
        sourceDirs += file
      } else {
        val fileObj = AbstractFile.getFile(entry)
        if (
          fileObj != null && (entry.endsWith(".scala") || entry
            .endsWith(".java"))
        ) {
          sourceFiles += SourceFile(fileObj, fileObj.toCharArray)
        }
      }
    }

    // Recursively find all Scala and Java files in directories
    val allRecursive = sourceDirs.flatMap { dir =>
      sourcesIn(dir, "scala", "java")
    }

    (sourceFiles ++ allRecursive).toSeq
  }

  /**
   * Recursively find all source files with given extensions in a directory.
   */
  private def sourcesIn(
      dir: AbstractFile,
      extensions: String*
  ): Seq[SourceFile] = {
    dir.iterator.toSeq.flatMap { file =>
      if (file.isDirectory) {
        sourcesIn(file, extensions*)
      } else {
        val fileName = file.name.toLowerCase
        if (extensions.exists(ext => fileName.endsWith(s".$ext"))) {
          Seq(SourceFile(file, file.toCharArray))
        } else {
          Seq.empty[SourceFile]
        }
      }
    }
  }
}
