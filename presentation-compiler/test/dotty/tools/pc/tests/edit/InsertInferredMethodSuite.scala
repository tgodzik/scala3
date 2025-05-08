package dotty.tools.pc.tests.edit

import dotty.tools.pc.base.BaseCodeActionSuite
import org.junit.Test
import dotty.tools.pc.utils.TextEdits
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import scala.meta.internal.metals.CompilerOffsetParams
import java.net.URI
import scala.meta.pc.CodeActionId
import org.eclipse.lsp4j as l
import scala.meta.internal.jdk.CollectionConverters.*
import java.util.Optional

class InsertInferredMethodSuite extends BaseCodeActionSuite:

  @Test def `simple` =
    checkEdit(
      """|
         |trait Main {
         |  def method1(s : String) = 123
         |
         |  method1(<<otherMethod>>(1))
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def method1(s : String) = 123
         |
         |  def otherMethod(arg0: Int): String = ???
         |  method1(otherMethod(1))
         |}
         |""".stripMargin
    )

  @Test def `backtick` =
    checkEdit(
      """|
         |trait Main {
         |  def method1(s : String) = 123
         |
         |  method1(<<`otherM ? ethod`>>(1))
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def method1(s : String) = 123
         |
         |  def `otherM ? ethod`(arg0: Int): String = ???
         |  method1(`otherM ? ethod`(1))
         |}
         |""".stripMargin
    )

  @Test def `simple-with-expression` =
    checkEdit(
      """|
         |trait Main {
         |  def method1(s : String) = 123
         |
         |  method1(<<otherMethod>>( (1 + 123).toDouble ))
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def method1(s : String) = 123
         |
         |  def otherMethod(arg0: Double): String = ???
         |  method1(otherMethod( (1 + 123).toDouble ))
         |}
         |""".stripMargin
    )

  @Test def `custom-type` =
    checkEdit(
      """|
         |trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |    val user = User(1)
         |
         |    method1(0.0, <<otherMethod>>(user, 1))
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |    val user = User(1)
         |
         |    def otherMethod(arg0: User, arg1: Int): String = ???
         |    method1(0.0, otherMethod(user, 1))
         |}
         |""".stripMargin
    )

  @Test def `custom-type2` =
    checkEdit(
      """|
         |trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |    val user = User(1)
         |    <<otherMethod>>(user, 1)
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |    val user = User(1)
         |    def otherMethod(arg0: User, arg1: Int) = ???
         |    otherMethod(user, 1)
         |}
         |""".stripMargin
    )

  @Test def `custom-type-advanced` =
    checkEdit(
      """|
         |trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |
         |    <<otherMethod>>(User(1), 1)
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |
         |    def otherMethod(arg0: User, arg1: Int) = ???
         |    otherMethod(User(1), 1)
         |}
         |""".stripMargin
    )

  @Test def `custom-type-advanced-more` =
    checkEdit(
      """|
         |trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |
         |    <<otherMethod>>(List(Set(User(1))), Map("1" -> 1))
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |    def method1(b: Double, s : String) = 123
         |
         |    case class User(i : Int)
         |
         |    def otherMethod(arg0: List[Set[User]], arg1: Map[String, Int]) = ???
         |    otherMethod(List(Set(User(1))), Map("1" -> 1))
         |}
         |""".stripMargin
    )

  @Test def `with-imports` =
    checkEdit(
      """|import java.nio.file.Files
         |
         |trait Main {
         |  def main() = {
         |    def method1(s : String) = 123
         |      val path = Files.createTempDirectory("")
         |      method1(<<otherMethod>>(path))
         |    }
         |}
         |
         |""".stripMargin,
      """|import java.nio.file.Files
         |import java.nio.file.Path
         |
         |trait Main {
         |  def main() = {
         |    def method1(s : String) = 123
         |      val path = Files.createTempDirectory("")
         |      def otherMethod(arg0: Path): String = ???
         |      method1(otherMethod(path))
         |    }
         |}
         |""".stripMargin
    )

  @Test def `lambda` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    def method1(s : String => Int) = 123
         |    method1(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    def method1(s : String => Int) = 123
         |    def otherMethod(arg0: String): Int = ???
         |    method1(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-2` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    def method1(s : (String, Float) => Int) = 123
         |    method1(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    def method1(s : (String, Float) => Int) = 123
         |    def otherMethod(arg0: String, arg1: Float): Int = ???
         |    method1(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-0` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    def method1(s : => Int) = 123
         |    method1(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|
         |trait Main {
         |  def main() = {
         |    def method1(s : => Int) = 123
         |    def otherMethod: Int = ???
         |    method1(otherMethod)
         |  }
         |}
         |
         |""".stripMargin
    )

  @Test def `lambda-0-with-fn-arg` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    def method1(s : String => Int) = s("123")
         |    method1(<<lol>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|
         |trait Main {
         |  def main() = {
         |    def method1(s : String => Int) = s("123")
         |    def lol(arg0: String): Int = ???
         |    method1(lol)
         |  }
         |}
         |
         |""".stripMargin
    )

  @Test def `lambda-generic` = // TODO: this and below are not working
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    val list = List(1, 2, 3)
         |    list.map(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    val list = List(1, 2, 3)
         |    def otherMethod(arg0: Int): Any = ???
         |    list.map(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-generic-chain-type-list` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    List((1, 2, 3)).filter(_ => true).map(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def otherMethod(arg0: (Int, Int, Int)) = ???
         |  def main() = {
         |    List((1, 2, 3)).filter(_ => true).map(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-generic-complex-type-list` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    List((1, 2, 3)).map(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def otherMethod(arg0: (Int, Int, Int)) = ???
         |  def main() = {
         |    List((1, 2, 3)).map(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-def-complex-type-list` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    val res = List((1, 2, 3)).map(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    def otherMethod(arg0: (Int, Int, Int)) = ???
         |    val res = List((1, 2, 3)).map(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-generic-complex-type` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    val list = List((1, 2, 3))
         |    list.map(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    val list = List((1, 2, 3))
         |    def otherMethod(arg0: (Int, Int, Int)) = ???
         |    list.map(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-generic-filter` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    val list = List(1, 2, 3)
         |    list.filter(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    val list = List(1, 2, 3)
         |    def otherMethod(arg0: Int): Boolean = ???
         |    list.filter(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `method-in-class` =
    checkEdit(
      """|
         |class User(i: Int){
         |  def map(s: String => Int) = i
         |}
         |trait Main {
         |  def main() = {
         |    val user = new User(1)
         |    user.map(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      """|class User(i: Int){
         |  def map(s: String => Int) = i
         |}
         |trait Main {
         |  def main() = {
         |    val user = new User(1)
         |    def otherMethod(arg0: String): Int = ???
         |    user.map(otherMethod)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-generic-foldLeft` =
    checkError(
      """|
         |trait Main {
         |  def main() = {
         |    val list = List(1, 2, 3)
         |    list.foldLeft(0)(<<otherMethod>>)
         |  }
         |}
         |
         |""".stripMargin,
      "Could not infer method for `otherMethod`, please report an issue in github.com/scalameta/metals"
    )

  @Test def `lambda-generic-with-arguments` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    val list = List((1, 2))
         |    list.map{case (x,y) => <<otherMethod>>(x,y)}
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    val list = List((1, 2))
         |    def otherMethod(arg0: Int, arg1: Int) = ???
         |    list.map{case (x,y) => otherMethod(x,y)}
         |  }
         |}
         |""".stripMargin
    )

  @Test def `lambda-generic-with-mixed-arguments` =
    checkEdit(
      """|
         |trait Main {
         |  def main() = {
         |    val y = "hi"
         |    val list = List(1)
         |    list.map(x => <<otherMethod>>(x,y))
         |  }
         |}
         |
         |""".stripMargin,
      """|trait Main {
         |  def main() = {
         |    val y = "hi"
         |    val list = List(1)
         |    def otherMethod(arg0: Int, arg1: String) = ???
         |    list.map(x => otherMethod(x,y))
         |  }
         |}
         |""".stripMargin
    )

  @Test def `class-method-with-no-body` =
    checkEdit(
      """|
         |class X()
         |trait Main {
         |  def main() = {
         |    val x = new X()
         |    val a = true
         |    val b = "test"
         |    x.<<otherMethod>>(a, b, 1)
         |  }
         |}
         |
         |""".stripMargin,
      """|class X() {
         |  def otherMethod(arg0: Boolean, arg1: String, arg2: Int) = ???
         |}
         |trait Main {
         |  def main() = {
         |    val x = new X()
         |    val a = true
         |    val b = "test"
         |    x.otherMethod(a, b, 1)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `trait-with-added` =
    checkEdit(
      """|
         |trait X
         |trait Main {
         |  def main() = {
         |    val x = new X { }
         |    val a = true
         |    val b = "test"
         |    x.<<otherMethod>>(a, b, 1)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait X {
         |  def otherMethod(arg0: Boolean, arg1: String, arg2: Int) = ???
         |}
         |trait Main {
         |  def main() = {
         |    val x = new X { }
         |    val a = true
         |    val b = "test"
         |    x.otherMethod(a, b, 1)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `class-method-with-empty-body` =
    checkEdit(
      """|
         |class X() {}
         |trait Main {
         |  def main() = {
         |    val x = new X()
         |    val a = true
         |    val b = "test"
         |    x.<<otherMethod>>(a, b, 1)
         |  }
         |}
         |
         |""".stripMargin,
      """|class X() {
         |  def otherMethod(arg0: Boolean, arg1: String, arg2: Int) = ???
         |}
         |trait Main {
         |  def main() = {
         |    val x = new X()
         |    val a = true
         |    val b = "test"
         |    x.otherMethod(a, b, 1)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `class-method-with-body` =
    checkEdit(
      """|
         |class X() {
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    val x = new X()
         |    val a = true
         |    val b = "test"
         |    x.<<otherMethod>>(a, b, 1)
         |  }
         |}
         |
         |""".stripMargin,
      """|class X() {
         |  def otherMethod(arg0: Boolean, arg1: String, arg2: Int) = ???
         |
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    val x = new X()
         |    val a = true
         |    val b = "test"
         |    x.otherMethod(a, b, 1)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `trait-method-with-body` =
    checkEdit(
      """|
         |trait X {
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    val x: X = ???
         |    val a = true
         |    val b = "test"
         |    x.<<otherMethod>>(a, b, 1)
         |  }
         |}
         |
         |""".stripMargin,
      """|trait X {
         |  def otherMethod(arg0: Boolean, arg1: String, arg2: Int) = ???
         |
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    val x: X = ???
         |    val a = true
         |    val b = "test"
         |    x.otherMethod(a, b, 1)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `object-method-with-body` =
    checkEdit(
      """|
         |object X {
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    val a = true
         |    val b = "test"
         |    X.<<otherMethod>>(a, b, 1)
         |  }
         |}
         |
         |""".stripMargin,
      """|object X {
         |  def otherMethod(arg0: Boolean, arg1: String, arg2: Int) = ???
         |
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    val a = true
         |    val b = "test"
         |    X.otherMethod(a, b, 1)
         |  }
         |}
         |""".stripMargin
    )

  @Test def `object-method-without-args` =
    checkEdit(
      """|
         |object X {
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    X.<<otherMethod>>
         |  }
         |}
         |
         |""".stripMargin,
      """|object X {
         |  def otherMethod = ???
         |
         |  val x = 1
         |}
         |trait Main {
         |  def main() = {
         |    X.otherMethod
         |  }
         |}
         |""".stripMargin
    )

  def checkEdit(
      original: String,
      expected: String
  ): Unit =
      val edits = getInferredMethod(original)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(expected, obtained)

  def checkError(
      original: String,
      expectedError: String
  ): Unit = {
      Try(getInferredMethod(original)) match {
        case Failure(exception: Throwable) =>
          assertNoDiff(
            expectedError,
            exception.getCause().getMessage().replaceAll("\\[.*\\]", "")
          )
        case Success(_) =>
          fail("Expected an error but got a result")
      }
    }

  def getInferredMethod(
      original: String,
      filename: String = "file:/A.scala"
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)

    val result = presentationCompiler
      .codeAction(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken),
        CodeActionId.InsertInferredMethod,
        Optional.empty()
      )
      .get()
    result.asScala.toList
  }


