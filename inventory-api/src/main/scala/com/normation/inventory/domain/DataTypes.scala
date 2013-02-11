/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.domain


import com.normation.utils.Utils._
import com.normation.utils.HashcodeCaching

/**
 * A file that contains all the simple data types, like Version,
 * MemorySize, Manufacturer, etc.
 */


/**
 * A simple class to denote a manufacturer
 * TODO : Should be merge with SoftwareEditor
 */
final case class Manufacturer(name:String) extends HashcodeCaching { assert(!isEmpty(name)) }

/**
 * A simple class to denote a softwareeditor
 */
final case class SoftwareEditor(val name:String) extends HashcodeCaching { assert(!isEmpty(name)) }

/**
 * A simple class to denote a software cryptographic public key
 */
final case class PublicKey(val key : String) extends HashcodeCaching { assert(!isEmpty(key)) }


/**
 * A simple class to denote version
 * Sub-class may be done to be specialized, like for
 * example "linux kernel version", "debian package version",
 * "ms patch version", etc.
 *
 * Comparison are really important in Version
 */
final class Version(val value:String) extends Comparable[Version] {
  require(nonEmpty(value))

  override def compareTo(other:Version) = this.value.compareTo(other.value)
  override def toString() = "[%s]".format(value)

  //subclass have to override that
  def canEqual(other:Any) = other.isInstanceOf[Version]

  override def hashCode() = 31 * value.hashCode

  override def equals(other:Any) = other match {
    case that:Version => (that canEqual this) && this.value == that.value
    case _ => false
  }

}