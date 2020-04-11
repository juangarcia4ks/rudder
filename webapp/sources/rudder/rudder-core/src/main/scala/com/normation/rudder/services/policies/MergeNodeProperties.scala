/*
*************************************************************************************
* Copyright 2020 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.services.policies

import com.normation.rudder.domain.nodes.CompareProperties
import com.normation.rudder.domain.nodes.GroupProperty
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.queries._
import com.normation.errors._
import com.normation.rudder.domain.policies.FullCompositeRuleTarget
import com.normation.rudder.domain.policies.FullOtherTarget
import GroupProp._
import com.normation.rudder.domain.nodes.NodeProperty
import com.softwaremill.quicklens._
import net.liftweb.json.JsonAST.JNothing

/**
 * This file handle how node properties are merged with other (global, groups, etc)
 * properties.
 * Merge happens during policy generation and only spans that policy generation, ie nodes
 * don't really have these properties: if you observe a node (for ex with API), you won't see them.
 *
 * Merge rules are the following:
 * Overriding is authorized for same keys IF AND ONLY IF:
 * - on a group by another group, if the second group is a subgroup of the first,
 *   i.e if he has a "AND" query composition, same nature (static/dynamic), and
 *   a `SubGroupComparator` criterion with value the parent groupid.
 * - on a node, if provider if the same.
 *
 * When overriding happens, we use a merge-override strategy, ie:
 * - if the overriding value is a simple value or an array, it replaces the previous one
 * - if the overriding value is an object and the overriden value is a simple type or an array,
 *   the latter one is replaced by the former
 * - if both overriding/overriden are arrays, overriding values are appended to overriden array
 * - if both overriding/overriden are objects, then each key is overriden recursively as explained,
 *   and new keys are added.
 */


/**
 * Utility that represents a group with just the interesting things for us.
 */
final case class GroupProp(
    properties  : List[GroupProperty]
  , groupId     : NodeGroupId
  , condition   : CriterionComposition
  , isDynamic   : Boolean
  , parentGroups: List[String] // groupd ids - a list because order is important!
  , groupName   : String // for error
)

object GroupProp {

  implicit class ToOneGroupProp(g: GroupProp) {
    def toOneGroupProps: Map[String, OneGroupProp] = {
      g.properties.map { p =>
        (
          p.name
        , OneGroupProp(
              p
            , g.groupId
            , g.condition
            , g.isDynamic
            , g.parentGroups
            , g.groupName
          )
        )
      }.toMap
    }
  }

  /**
   * Utility class to transform RuleTarget (which are the things where we get when
   * we resolve node belongings, for some reason I don't know about) into GroupProp
   */
  implicit class FromTarget(target: FullRuleTargetInfo) {
    def toGroupProp: PureResult[GroupProp] = {
      target.target match {
        case FullCompositeRuleTarget(t) =>
          Left(Unexpected(s"There is a composite target in group definition, it's likely a dev error: '${target.name}'"))
        case FullOtherTarget(t) => //taget:all nodes, only root, only managed node, etc
          Right(GroupProp(
              Nil   // they don't have properties for now
            , NodeGroupId(t.target)
            , And   // for simplification, since they don't have properties it doesn't matter
            , true  // these special targets behave as dynamic groups
            , Nil   // terminal leaf
            , target.name
          ))
        case FullGroupTarget(t, g) =>
          g.query match {
            case None =>
              // if group doesn't has a query: not sure. Error ? Default ?
              Right(GroupProp(
                  g.properties
                , g.id
                , And
                , g.isDynamic
                , Nil   // terminal leaf
                , target.name
              ))
            case Some(q) =>
              Right(GroupProp(
                  g.properties
                , g.id
                , q.composition
                , g.isDynamic
                , q.criteria.flatMap {
                    // we are only interested in subgroup criterion with AND, and we want to
                    // keep order for overriding priority.
                    case CriterionLine(_, a, _, value) if(q.composition == And && a.cType.isInstanceOf[SubGroupComparator]) => Some(value)
                    case _ => None
                  }
                , target.name
              ))
          }
      }
    }
  }
}

/**
 * A group property with information about the group in which
 * it is defined.
 */
final case class OneGroupProp(
    property    : GroupProperty
  , groupId     : NodeGroupId
  , condition   : CriterionComposition
  , isDynamic   : Boolean
  , parentGroups: List[String] // groupd ids - a list because order is important!
  , groupName   : String // for error
)

/**
 * We need a tree structure to modelize sub-group relationship.
 * The tree starts from root and go up to parents, and the outer most
 * parents are leaf. Like a real tree. You know.
 */
final case class InvertedTree[A](value: A, parents: List[InvertedTree[A]]) {
  // we need to be able to test for a "contains" on a A:
  def contains(predicat: A => Boolean): Boolean = {
    predicat(value) || parents.exists(_.contains(predicat))
  }

  // change content of nodes
  def map[B](f: A => B): InvertedTree[B] = InvertedTree(f(value), parents.map(_.map(f)))
}


object MergeNodeProperties {

  def withGroups(node: NodeInfo, nodeTargets: List[FullRuleTargetInfo]): PureResult[NodeInfo] = {
    import com.softwaremill.quicklens._
    for {
      props <- checkPropertyMerge(nodeTargets)
      // merge with node
      // node value has priority
      res   <- CompareProperties.updateProperties(props.map(p => new NodeProperty(p.name, p.value, None)), Some(node.properties)).map(p =>
                 node.modify(_.node.properties).setTo(p)
               )
    } yield res
  }

  /**
   * Check that the given list of group is a legal set of properties, ie one of:
   * - all properties have different keys,
   * - if n groups share a key, these n groups are in an inheritance relation,
   *   i.e they have a subgroup chain.
   *   In that case, properties are merged according to the merge function.
   *
   * We know that we have all groups/subgroups in which the node is here.
   */
  def checkPropertyMerge(targets: List[FullRuleTargetInfo]): PureResult[List[GroupProperty]] = {
    /*
     * General strategy:
     * - build all disjoint hierarchies of trees that contains that node
     *   (a tree is defined by our inherance rules, so we can perfectly have
     *   n overlapping groups for the set of nodes they contains that are not
     *   in a hierarchy).
     * - for each tree, resolve overriding in properties
     * - then, merge all resulting properties. At that point, two properties with the
     *   same key are in conflict (by our definition of "not in conflict only if
     *   they are in the same hierarchy").
     */


    /*
     * For a tree, merge properties and for each key, return the deepest child with that property.
     * The merge is done with rules explain above, and priority when there is several
     * parent is done by starting with the first group in the criterion line - the last
     * group in criteria is the one which wins.
     * We do the merge recursively and by starting by the most prioritary group each time,
     * depth first, so that once a property is defined, its value is never replaced (but it can
     * be augmented in the case of object, but here again only for new keys).
     *
     * /!\ /!\ /!\ We assume that the list of parents in the order of criterion
     * lines, so that the HEAD is the LEAST prioritary parent (ie the one which will
     * have the most of its values changed).
     */
    def overrideTree(tree: InvertedTree[GroupProp]): Map[String, OneGroupProp] = {
      val current = tree.value.toOneGroupProps
      tree.parents match {
        case Nil  => current
        case many =>
          // depth first
          overrideValues(many.map(overrideTree) :+ current)
      }
    }

    /*
     * merge overriding properties with overriden one. Here, conflict resolution was done,
     * so we just want to merge according to following rules:
     * - if the overriding value is a simple value or an array, it replaces the previous one
     * - if the overriding value is an object and the overriden value is a simple type or an array,
     *   the latter one is replaced by the former
     * - if both overriding/overriden are array, then overriding values are appended to overriden array
     * - if both overriding/overriden are object, then each key is overriden recursively as explained,
     *   and new keys are added.
     * See https://github.com/lift/lift/tree/master/framework/lift-base/lift-json/#merging--diffing
     * for more information.
     * The most prioritary is the last in the list
     */
    def overrideValues(overriding: List[Map[String, OneGroupProp]]): Map[String, OneGroupProp] = {
      overriding.foldLeft(Map[String, OneGroupProp]()){ case (old, newer) =>
        // we need to keep the child group for value reference
        val merged = newer.map { case(k, v) =>
          val oldValue = old.get(k).map(_.property.value).getOrElse(JNothing)
          (k, v.modify(_.property).using(g => g.modify(_.value).using(jv => oldValue.merge(jv))))
        }
        old ++ merged
      }
    }

    /*
     * Last merge: check if any property is defined in at least two groups.
     * If it's the case, report error, else return all properties
     */
    def mergeAll(propByTrees: List[OneGroupProp]): PureResult[List[OneGroupProp]] = {
      val grouped = propByTrees.groupBy(_.property.name).map {
        case (_, Nil)    => Left(Unexpected(s"A groupBY lead to an empty group. This is a developper bug, please report it."))
        case (_, h::Nil) => Right(h)
        case (k, h::more)   =>
          if(more.forall(_.property == h.property)) {
            // if it's exactly the same property everywhere, it's ok
            Right(h)
          } else {
            Left(Inconsistency(s"Error when trying to find overrides for group property '${k}'. " +
                             s"Several groups which are not in an inheritance relation define it. You will need to define " +
                             s"a new group with all these groups as parent and choose the order on which you want to do " +
                             s"the override by hand. Faulty groups: ${(h::more).map(m => s"'${m.groupName}' (${m.groupId.value})").mkString(", ")}"
            ))
          }
      }
      grouped.accumulatePure(identity)
    }

    for {
      trees     <- builtTrees(targets)
      overriden =  trees.map(overrideTree).flatMap(_.values)
      merged    <- mergeAll(overriden)
    } yield {
      merged.map(_.property)
    }
  }

  /*
   * For a same property identified by key, check that all groups are
   * in a hierarchy and sort them from outermost parent in head to
   * last children in tail
   */
  def builtTrees(targets: List[FullRuleTargetInfo]): PureResult[List[InvertedTree[GroupProp]]] = {
    /*
     * The recusrive function: process group in `remains` and add it to `done`.
     * Each step can fail if we miss some group reference.
     */
    def recurseTrees(allTargets: Map[NodeGroupId, GroupProp], remain: List[GroupProp], done: List[InvertedTree[GroupProp]]): PureResult[List[InvertedTree[GroupProp]]] = {
      remain match {
        case Nil => Right(done)
        case h::tail =>
          buildOneTree(h, allTargets).flatMap(tree =>
            // remove groups which were processed in that hierarchy
            recurseTrees(allTargets, tail.filterNot(g => tree.contains(_.groupId == g.groupId)), tree :: done)
          )
      }
    }

    for {
      all   <- targets.accumulatePure(_.toGroupProp.map(g => (g.groupId, g))).map(_.toMap)
      trees <- recurseTrees(all, all.values.toList, Nil)
    } yield {
      trees
    }
  }


  /*
   * Build a tree from given group with the `allTargets` context which is supposed to
   * contain all group related to that one and more.
   * Algorithm to build hierarchy:
   * - take one of the group at random (the one given in parameter)
   * - search for root, ie recursively search for one child having parameter group as parent
   *     - 0 means we have the last child: root
   *     - 1 means more children! Recurse.
   *     - n > 1 means a diamond hierarchy. Take one branche at random, the other
   *       one will be processed in another call to parent method. Recurse!
   *
   * When we are at root, we need to go back up, actually building the tree with all
   * its branches and not only the one we took during descent:
   *   - look at each line in criterion (we only have subgroup criterion here), keeping line
   *     order (because it defines override order):
   *     - 0 means we have the outer most parent
   *     - n > 0 means: more parents!
   */
  def buildOneTree(group: GroupProp, allTargets: Map[NodeGroupId, GroupProp]): PureResult[InvertedTree[GroupProp]] = {
    /*
     * Descend to root
     */
    @scala.annotation.tailrec
    def searchDown(current: GroupProp, all: Map[NodeGroupId, GroupProp]): PureResult[InvertedTree[GroupProp]] = {
      all.collect { case g if g._2.parentGroups.contains(current.groupId.value) => g._2 } match {
        case Nil      => // found root, go up now
          buildUp(current, all)
        case h :: _ => // recurse
          searchDown(h, all)
      }
    }

    /*
     * Here, we look up for parent groups. We know that "all" contains all parents.
     */
    def buildUp(root: GroupProp, all: Map[NodeGroupId, GroupProp]): PureResult[InvertedTree[GroupProp]] = {
      // recursively takes parent, keeping criterion line order
      for {
        parents <- root.parentGroups.accumulatePure { id =>
                     all.get(NodeGroupId(id)) match {
                       case None    => Left(Inconsistency(s"Group '${root.groupName}' (${root.groupId.value}) has a group comparator " +
                                                       s"criterion but the corresponding group was not found: ${id}"))
                       case Some(g) => buildUp(g, all)
                     }
                   }
      } yield {
        InvertedTree(root, parents)
      }
    }

    searchDown(group, allTargets)
  }

}

