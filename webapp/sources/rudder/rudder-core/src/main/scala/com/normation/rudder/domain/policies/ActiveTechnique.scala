/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.domain.policies

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import org.joda.time.DateTime

final case class ActiveTechniqueId(value: String) extends AnyVal

/**
 * An active technique is a technique from the technique library
 * that Rudder administrator choose to mark as "can be used to
 * derive directive from".
 * Non activated techniques are not available to build
 * directives.
 *
 * ActiveTechniques are grouped in the "Active Techniques" set,
 * organized in categories.
 *
 * An active technique keep metadatas about when the technique was
 * activated, what version of the technique are activated, what
 * directives use it.
 */
final case class ActiveTechnique(
    id                  : ActiveTechniqueId
  , techniqueName       : TechniqueName
  , acceptationDatetimes: Map[TechniqueVersion, DateTime]
    //TODO: remove directives ids, they DON'T have to be here.
  , directives          : List[DirectiveUid] = Nil
  , _isEnabled          : Boolean = true
  , isSystem            : Boolean = false
) {
  //system object must ALWAYS be ENABLED.
  def isEnabled = _isEnabled || isSystem
}

