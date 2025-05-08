package dotty.tools.pc

import java.net.URI
import java.nio.file.Paths
import scala.annotation.tailrec
import scala.util.Random
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.ReportContext
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j as l
import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.pc.printer.ShortenedTypePrinter
import dotty.tools.pc.printer.ShortenedTypePrinter.IncludeDefaultParam
import dotty.tools.pc.utils.InteractiveEnrichments._
import dotty.tools.pc.IndexedContext
import dotty.tools.pc.AutoImports
import dotty.tools.pc.AutoImports.AutoImportsGenerator
import dotty.tools.pc.MetalsInteractive
import java.util.Optional

/**
 * Tries to calculate edits needed to create a method that will fix missing symbol
 * in all the places that it is possible such as:
 * - apply inside method invocation `method(nonExistent(param))`
 * - method in val definition `val value: DefinedType = nonExistent(param)` TODO
 * - lambda expression `list.map(nonExistent)`
 * - class method `someClass.nonExistent(param)`
 *
 * Limitations:
 *   - cannot work with an expression inside the parameter, since it's not typechecked
 * @param driver Scala 3 interactive compiler driver
 * @param params position and actual source
 * @param config presentation compiler config
 * @param search symbol search
 */
final class InferredMethodProvider(
    params: OffsetParams,
    driver: InteractiveDriver,
    config: PresentationCompilerConfig,
    search: SymbolSearch
)(using ReportContext):

  object ApplyOrTypeApply:
    def unapply(tree: Tree): Option[(Tree, List[Tree])] = tree match
      case Apply(TypeApply(inside, args), _) => Some((inside, args))
      case Apply(inside, args) => Some((inside, args))
      case _ => None

  private val uri = params.uri().nn
  private val text = params.text().nn
  private val filePath = Paths.get(uri)
  private val source = SourceFile.virtual(filePath.toString, text)
  driver.run(uri, source)
  private val unit = driver.currentCtx.run.nn.units.head
  private val pos = driver.sourcePosition(params)
  private val path = Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
  given locatedCtx: Context = driver.localContext(params)
  private val indexedCtx = IndexedContext(pos)(using locatedCtx)
  private val autoImportsGen = AutoImports.generator(
    pos,
    text,
    unit.tpdTree,
    unit.comments,
    indexedCtx,
    config
  )
  private val printer = ShortenedTypePrinter(
    search,
    includeDefaultParam = IncludeDefaultParam.ResolveLater,
    isTextEdit = true
  )(using indexedCtx)

  def inferredMethodEdits(): Either[String, List[TextEdit]] =
    path match
      case (errorMethod: Ident) :: _ if !errorMethod.symbol.exists =>
        val errorMethodName = errorMethod.name.decoded.backticked
        path match
          // method(nonExistent(param))
          case _ :: (nonExistent: Apply) :: Apply(
                (ident @ Ident(containingMethodName)), // when is a method
                arguments
              ) :: _ if !nonExistent.symbol.exists =>
            makeEditsForApplyWithUnknownName(
              arguments,
              errorMethodName,
              nonExistent,
              ident.symbol
            )
          case _ :: (nonExistent: Apply) :: Apply(
                 (sel @ Select(_, containingMethodName)), // when is a class method
                arguments
              ) :: _ if !nonExistent.symbol.exists =>
            makeEditsForApplyWithUnknownName(
              arguments,
              errorMethodName,
              nonExistent,
              sel.symbol
            )
          // <<nonExistent>>(param1, param2)
          case (_: Ident) :: Apply(
                containing @ Ident(
                  nonExistent
                ),
                arguments
              ) :: _ if !containing.symbol.exists =>
            signature(
              name = nonExistent.toString,
              paramsString = Option(argumentsString(arguments).getOrElse("")),
              retType = None,
              postProcess = identity,
              position = None
            )
          // List(1, 2, 3).map(<<nonExistent>>)
          case (_: Ident) :: Apply(
                Ident(containingMethod),
                arguments
              ) :: _ =>
            makeEditsForListApply(arguments, containingMethod, errorMethodName)
          // List(1,2,3).map(myFn)
          case (_: Ident) :: Apply(
                Select(apply @ Apply(_, _), _),
                _
              ) :: _ =>
            modifyAndInferAgain(apply)
          // list.map(nonExistent)
          case (Ident(_)) :: ApplyOrTypeApply(
                selectTree @ Select(qual @ Ident(_), _),
                _ :: Nil
              ) :: _ =>

            makeEditsForListApplyWithoutArgs(
              qual,
              selectTree,
              errorMethodName
            )
          case _ =>
            println(s"path: ${path.take(4)}")
            unimplemented(errorMethodName)
      case (errorMethod: Select) :: _ =>
        val errorMethodName = errorMethod.name.decoded.backticked
        path match
          // x.nonExistent(1,true,"string")
          case Select(Ident(container), _) :: Apply(
                _,
                arguments
              ) :: _ =>
            makeEditsMethodObject(Some(arguments), container, errorMethodName)
          // X.<<nonExistent>>
          case Select(Ident(container), _) :: _ =>
            makeEditsMethodObject(None, container, errorMethodName)
          // X.<<nonExistent>>
          case Select(Select(_, container), _) :: _ =>
            makeEditsMethodObject(None, container, errorMethodName)
          case _ =>
            unimplemented(errorMethodName)
      case tree :: _ =>
        println(s"Matched: fallback tree: ${tree.getClass.getSimpleName}")
        unimplemented(tree.show)
      case Nil =>
        println("Matched: Nil")
        Left("No tree found at position")

  private def additionalImports: List[TextEdit] =
    printer.imports(autoImportsGen)

  private def prettyType(tpe: Type): String =
    printer.tpe(tpe.widen.finalResultType)

  private def argumentsString(args: List[Tree]): Option[String] =
    val paramsTypes = args.zipWithIndex
      .map { case (arg, index) =>
        val tp = arg match
          case Ident(name) =>
            indexedCtx.findSymbol(name).flatMap(_.headOption) match
              case Some(sym) => Some(prettyType(sym.info))
              case None => None
          case _ =>
            val typ = arg.tpe
            if ((typ ne null) && !typ.isError)
              Some(prettyType(typ))
            else Some("Any")
        tp.map(tp => s"arg$index: $tp")
      }
    if (paramsTypes.forall(_.nonEmpty))
      Some(paramsTypes.flatten.mkString(", "))
    else None

  private def getPositionUri(position: SourcePosition): String =
    Option(position.source).map(_.file.toString).getOrElse(uri.toString)

  private def unimplemented(name: String): Either[String, List[TextEdit]] =
    println(Thread.currentThread().getStackTrace().mkString("\n"))
    Left(s"Could not infer method for `$name`, please report an issue in github.com/scala/scala3")

  private def modifyAndInferAgain(
      untyped: Tree
  ): Either[String, List[TextEdit]] =
    val enclosingStatementPos = insertPosition()
    val internalName = "$metals_internal"
    val before = text.substring(0, enclosingStatementPos.start)
    val after = text.substring(enclosingStatementPos.start, untyped.sourcePos.start) +
      internalName +
      text.substring(untyped.sourcePos.end)
    val internalVal = s"val $internalName = ${untyped.show};"
    val updatedText = s"${before}${internalVal}$after"
    val removedTreeLength = untyped.sourcePos.end - untyped.sourcePos.start
    val addedCodeLength =
      internalVal.length - removedTreeLength + internalName.size
    val newParams =
      new CompilerOffsetParams(
        URI.create("InferMethod" + Random.nextLong() + ".scala"),
        updatedText,
        params.offset() + addedCodeLength,
        params.token(),
        Optional.empty()
      )
    val insertRange = insertPosition().toLsp
    val ident = "\n" + " " * insertRange.getStart().getCharacter()
    val provider = new InferredMethodProvider(newParams, driver, config, search)
    provider.inferredMethodEdits() match
      case Right(List(edit)) =>
        insertRange.setEnd(insertRange.getStart())
        edit.setRange(insertRange)
        edit.setNewText(edit.getNewText().trim() + ident)
        Right(List(edit))
      case otherwise => otherwise

  private def signature(
      name: String,
      paramsString: Option[String],
      retType: Option[String],
      postProcess: String => String,
      position: Option[SourcePosition]
  ): Either[String, List[TextEdit]] =
    val lastApplyPos = position.getOrElse(insertPosition())
    val indentString = indentation(text, lastApplyPos.start - 1)
    val retTypeString = retType match
      case None => ""
      case Some(retTypeStr) => s": $retTypeStr"
    val parameters = paramsString.map(s => s"($s)").getOrElse("")
    val full =
      s"def ${name}$parameters$retTypeString = ???\n$indentString"
    val methodInsertPosition = lastApplyPos.toLsp
    methodInsertPosition.setEnd(methodInsertPosition.getStart())
    val newEdits = new TextEdit(
      methodInsertPosition,
      postProcess(full)
    ) :: additionalImports
    Right(newEdits)

  private def makeEditsForApplyWithUnknownName(
      arguments: List[Tree],
      errorMethodName: String,
      nonExistent: Apply,
      methodSymbol: Symbol
  ): Either[String, List[TextEdit]] =
    val argumentString = argumentsString(nonExistent.args)
    argumentString match
      case Some(paramsString) =>
        val retIndex = arguments.indexWhere(_.sourcePos.contains(pos))
        methodSymbol.info match
          case mt: MethodType if retIndex >= 0 =>
            val ret = prettyType(mt.paramInfos(retIndex))
            signature(
              name = errorMethodName,
              paramsString = Option(paramsString),
              retType = Some(ret),
              postProcess = identity,
              position = None
            )
          case _ =>
            unimplemented(errorMethodName)
      case _ =>
        unimplemented(errorMethodName)

  private def insertPosition() =
    val blockOrTemplateIndex =
      path.tail.indexWhere {
        case _: Block | _: Template => true
        case _ => false
      }
    path(blockOrTemplateIndex).sourcePos

  private def createParameters(params: List[Type]): String =
    params.zipWithIndex
      .map { case (p, index) =>
        s"arg$index: ${prettyType(p)}"
      }
      .mkString(", ")

  private def makeEditsForListApply(
      arguments: List[Tree],
      containingMethod: Name,
      errorMethodName: String
  ): Either[String, List[TextEdit]] =
    val methodSymbol = indexedCtx.findSymbol(containingMethod).flatMap(_.headOption)
    if (methodSymbol.isDefined) then
      val retIndex = arguments.indexWhere(_.sourcePos.contains(pos))
      methodSymbol.get.info match
        case mt: MethodType if retIndex >= 0 =>
          val lastApplyPos = insertPosition()
          val tpe = mt.paramInfos(retIndex)
          val editResult: Either[String, List[TextEdit]] = tpe match {
            case AppliedType(_, args) if args.nonEmpty =>
              val params = args.init
              val paramsString = createParameters(params)
              val resultType = args.last
              val ret = prettyType(resultType)
              signature(
                name = errorMethodName,
                Option(paramsString),
                Option(ret),
                identity,
                None
              )
            case _ =>
              val retTpe = tpe match
                case ExprType(resType) => resType
                case _ => tpe
              val ret = prettyType(retTpe)
              signature(
                name = errorMethodName,
                None,
                Option(ret),
                identity,
                Option(lastApplyPos)
              )
          }
          editResult
        case _ =>
          unimplemented(errorMethodName)
    else
      signature(
        name = errorMethodName,
        paramsString = Option(argumentsString(arguments).getOrElse("")),
        retType = None,
        postProcess = identity,
        position = None
      )

  private def makeEditsForListApplyWithoutArgs(
      qual: Ident,
      select: Select,
      errorMethodName: String
  ): Either[String, List[TextEdit]] =
    def signatureFromMethodType(sym: Symbol) =
      sym.info match
        case mt: MethodType if mt.paramNames.nonEmpty =>
          val paramsString = createParameters(mt.paramInfos)
          val returnTpe = Some(prettyType(mt.resultType))
          signature(
            name = errorMethodName,
            Option(paramsString),
            returnTpe,
            identity,
            None
          )
        case _ =>
          unimplemented(errorMethodName)

    def findMethodType(tpe: Type): Option[MethodType] =
      tpe match
        case p: PolyType => findMethodType(p.resultType)
        case m: MethodType => Some(m)
        case _ => None

    val selectTpe = select.tpe
    val foundType = if (selectTpe ne null) then selectTpe
      else indexedCtx.findSymbol(qual.name).flatMap(_.headOption).map(_.info).getOrElse(NoType)
    findMethodType(foundType) match
      case Some(mt: MethodType) if mt.paramNames.nonEmpty =>
        indexedCtx.findSymbol(qual.name).flatMap(_.headOption) match
          case Some(sym) => signatureFromMethodType(sym)
          case None => unimplemented(errorMethodName)
      case _ =>
        // foundType match
        //   case term: TermRef =>
        //     println(term.paramInfoss)
        //     println(term.resultType)
        //     // val paramsString = createParameters(term.paramInfoss.head)
        //     // println(paramsString)
        //     val returnTpe = Some(prettyType(term.resultType))
        //     println(returnTpe)
        //     signature(
        //       name = errorMethodName,
        //       None, // Option(paramsString),
        //       returnTpe,
        //       identity,
        //       None
        //     )

        unimplemented(errorMethodName)

  private def makeEditsMethodObject(
      arguments: Option[List[Tree]],
      container: Name,
      errorMethodName: String
  ): Either[String, List[TextEdit]] =
    def makeClassMethodEdits(
        template: Template
    ): Either[String, List[TextEdit]] =
      val insertPos: SourcePosition =
        inferEditPosition(template)
      val templateFileUri = Option(template.source).map(_.file.toString).getOrElse("")
      if (uri.toString != getPositionUri(insertPos)) then
        Left(
          "Inferring method only works in the current file, cannot add method to types outside of it."
        )
      else
        signature(
          name = errorMethodName,
          paramsString = arguments
            .flatMap(argumentsString(_).orElse(Some(""))),
          retType = None,
          postProcess = method => {
            if (hasBody(templateFileUri, template).isDefined)
              s"\n  $method"
            else s" {\n  $method}"
          },
          position = Some(insertPos)
        )

    val containerName = indexedCtx.findSymbol(container).flatMap(_.headOption)
    if (containerName.isDefined) then
      val containerSymbolType = containerName.get.info
      val classSymbol = containerSymbolType match
        case tr: TypeRef => Some(tr.typeSymbol)
        case rt: RefinedType if rt.parents.size > 1 =>
          rt.parents.tail.headOption.map(_.typeSymbol)
        case _ => None
      classSymbol match
        case Some(classSym) =>
          val containerClass = indexedCtx.findSymbol(classSym.name).flatMap(_.headOption)
          containerClass match
            case Some(sym) =>
              unimplemented(errorMethodName)
            case None =>
              unimplemented(errorMethodName)
        case None =>
          unimplemented(errorMethodName)
    else
      unimplemented(errorMethodName)

  private def indentation(text: String, pos: Int): String =
    if (pos > 0 && pos < text.length) then
      val isSpace = text(pos) == ' '
      val isTab = text(pos) == '\t'
      val indent = countIndent(text, pos, 0)
      if (isSpace) " " * indent else if (isTab) "\t" * indent else ""
    else ""

  @tailrec
  private def countIndent(text: String, index: Int, acc: Int): Int =
    if (index > 0 && text(index) != '\n') countIndent(text, index - 1, acc + 1)
    else acc

  private def inferEditPosition(t: Template): SourcePosition =
    val text = params.text().nn
    hasBody(text, t)
      .map { offset => t.sourcePos.withStart(offset + 1).withEnd(offset + 1) }
      .getOrElse(
        t.sourcePos.withStart(t.sourcePos.end)
      )

  private def hasBody(text: String, t: Template): Option[Int] =
    val start = t.sourcePos.start
    val offset =
      if (t.self.tpt.isEmpty)
        text.indexOf('{', start)
      else text.indexOf("=>", start) + 1
    if (offset > 0 && offset < t.sourcePos.end) Some(offset)
    else None

end InferredMethodProvider
