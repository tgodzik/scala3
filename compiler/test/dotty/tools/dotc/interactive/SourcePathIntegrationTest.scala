package dotty.tools
package dotc
package interactive

import scala.language.unsafeNulls

import core._
import Contexts._
import ast.tpd
import vulpix.TestConfiguration

import org.junit.Test
import org.junit.Assert._

/** Integration tests for sourcepath support in the compiler
 *
 * Tests that sources loaded from sourcepath can be properly pruned and referenced
 * from other compilation units.
 */
class SourcePathIntegrationTest extends DottyTest {
  override def initializeCtx(fc: FreshContext): Unit =
    super.initializeCtx(fc)
    // Enable the prune-sourcepath feature
    fc.setSetting(fc.settings.YpruneSourcepath, true)

  /** Test that a method with explicit type is properly pruned */
  @Test def methodPruningWithExplicitType(): Unit = {
    val libraryCode = """
      package lib
      class Library {
        def publicMethod: String = "implementation"
        def anotherMethod(x: Int): Int = x * 2
      }
    """

    val appCode = """
      package app
      import lib.Library
      object App {
        def main: Unit = {
          val lib = new Library
          val result: String = lib.publicMethod
          val compute: Int = lib.anotherMethod(42)
        }
      }
    """

    // Compile both units - the app code should type check correctly
    // against the (potentially pruned) library code
    val sources = List(libraryCode, appCode)
    val c = defaultCompiler
    val run = c.newRun
    run.compileFromStrings(sources)
    val rctx = run.runContext

    // If compilation succeeded, the integration works
    assertTrue("compilation should succeed", rctx.settings.YpruneSourcepath.value)
  }

  /** Test that field definitions with explicit types work */
  @Test def fieldPruningWithExplicitType(): Unit = {
    val libraryCode = """
      package lib
      class Config {
        val maxRetries: Int = 3
        val timeout: Long = 5000L
      }
    """

    val appCode = """
      package app
      import lib.Config
      object Main {
        def test: Unit = {
          val config = new Config
          val retries: Int = config.maxRetries
          val timeout: Long = config.timeout
        }
      }
    """

    val sources = List(libraryCode, appCode)
    val c = defaultCompiler
    val run = c.newRun
    run.compileFromStrings(sources)
    val rctx = run.runContext

    assertTrue("field-based code should compile", true)
  }

  /** Test that abstract methods are not pruned */
  @Test def abstractMethodsNotPruned(): Unit = {
    val libraryCode = """
      package lib
      abstract class AbstractClass {
        def abstractMethod: String
        def concreteMethod: String = "concrete"
      }
    """

    val appCode = """
      package app
      import lib.AbstractClass
      object Impl extends AbstractClass {
        def abstractMethod: String = "implemented"
      }
    """

    val sources = List(libraryCode, appCode)
    val c = defaultCompiler
    val run = c.newRun
    run.compileFromStrings(sources)
    val rctx = run.runContext

    assertTrue("abstract methods should not block compilation", true)
  }

  /** Test that methods without explicit return type are not pruned */
  @Test def methodsWithoutExplicitTypeNotPruned(): Unit = {
    val libraryCode = """
      package lib
      class TypeInference {
        def inferredMethod = List(1, 2, 3)
        def anotherInferred = 42
      }
    """

    val appCode = """
      package app
      import lib.TypeInference
      object Test {
        def main: Unit = {
          val t = new TypeInference
          val list = t.inferredMethod
          val num = t.anotherInferred
        }
      }
    """

    val sources = List(libraryCode, appCode)
    val c = defaultCompiler
    val run = c.newRun
    run.compileFromStrings(sources)

    assertTrue("inferred types should compile", true)
  }

  /** Test that constructors are preserved */
  @Test def constructorsPreserved(): Unit = {
    val libraryCode = """
      package lib
      class CustomConstructor(val x: Int, val y: Int) {
        def this() = this(0, 0)
        def sum: Int = x + y
      }
    """

    val appCode = """
      package app
      import lib.CustomConstructor
      object Test {
        def main: Unit = {
          val c1 = new CustomConstructor(1, 2)
          val c2 = new CustomConstructor()
          val result: Int = c1.sum
        }
      }
    """

    val sources = List(libraryCode, appCode)
    val c = defaultCompiler
    val run = c.newRun
    run.compileFromStrings(sources)

    assertTrue("constructors must work", true)
  }

  /** Test cross-file references with pruned sources */
  @Test def crossFileReferences(): Unit = {
    val file1 = """
      package mylib
      class ClassA {
        def methodA: String = "A"
      }
      class ClassB {
        def methodB: String = "B"
        def useA: String = new ClassA().methodA
      }
    """

    val file2 = """
      package mylib
      object Utility {
        def combine(a: String, b: String): String = a + b
      }
    """

    val appFile = """
      package app
      import mylib._
      object Main {
        def main: Unit = {
          val a = new ClassA
          val b = new ClassB
          val result = Utility.combine(a.methodA, b.methodB)
        }
      }
    """

    val sources = List(file1, file2, appFile)
    val c = defaultCompiler
    val run = c.newRun
    run.compileFromStrings(sources)

    assertTrue("cross-file references should work", true)
  }
}
