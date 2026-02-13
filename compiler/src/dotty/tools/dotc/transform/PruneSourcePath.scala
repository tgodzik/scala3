package dotty.tools.dotc
package transform

import core.*
import Contexts.*
import Symbols.*
import Flags.*
import Decorators.*
import MegaPhase.*
import ast.tpd

/** Prunes method and field bodies from sources loaded from sourcepath.
 *
 * This phase removes method bodies and field RHS expressions from compilation units
 * marked as `isFromSourcePath = true`, replacing them with `???`. Only elements with
 * explicit type annotations are pruned - those without types need their bodies for
 * type inference.
 *
 * Enabled by the -Yprune-sourcepath compiler flag.
 */
class PruneSourcePath extends MiniPhase:
  import tpd.*

  override def phaseName: String = "pruneSourcePath"

  override def description: String = "prune method bodies from sourcepath sources"

  override def isEnabled(using Context): Boolean =
    ctx.settings.YpruneSourcepath.value

  override def runsAfter: Set[String] = Set(typer.TyperPhase.name)

  override def transformDefDef(tree: DefDef)(using Context): Tree =
    // Only process files from sourcepath
    if !ctx.compilationUnit.isFromSourcePath then return tree

    val sym = tree.symbol

    // Keep these unchanged:
    // - Constructors (needed for initialization)
    // - Abstract/deferred methods (no body to prune)
    // - Macros (need full expansion)
    // - Inline methods (body needed for inlining)
    // - Methods in value classes (special semantics)
    if sym.isConstructor ||
       sym.is(Deferred) ||
       sym.is(Abstract) ||
       sym.is(Macro) ||
       sym.is(Inline) ||
       sym.owner.derivesFrom(defn.AnyValClass) ||
       tree.rhs.isEmpty then
      return tree

    // Only prune if there's an explicit type annotation
    // (methods without explicit types need their bodies for type inference)
    if tree.tpt.isEmpty then return tree

    // Replace body with ???
    cpy.DefDef(tree)(rhs = ref(defn.Predef_undefined))

  override def transformValDef(tree: ValDef)(using Context): Tree =
    // Only process files from sourcepath
    if !ctx.compilationUnit.isFromSourcePath then return tree

    val sym = tree.symbol

    // Keep these unchanged:
    // - Parameters (part of method signature)
    // - Abstract/deferred fields (no RHS to prune)
    // - Lazy vals (initialization semantics differ)
    // - Mutable vars (may have side effects)
    // - Case accessors (needed for case class functionality)
    if sym.is(Param) ||
       sym.is(Deferred) ||
       sym.is(Abstract) ||
       sym.is(Lazy) ||
       sym.is(Mutable) ||
       sym.is(CaseAccessor) ||
       tree.rhs.isEmpty then
      return tree

    // Only prune if there's an explicit type annotation
    if tree.tpt.isEmpty then return tree

    // Replace RHS with ???
    cpy.ValDef(tree)(rhs = ref(defn.Predef_undefined))

end PruneSourcePath
