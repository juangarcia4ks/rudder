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

package com.normation.rudder.web.comet

import scala.xml._
import net.liftweb.common._
import net.liftweb.util._
import Helpers._
import net.liftweb.http._
import js._
import JE._
import JsCmds._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.batch._
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.model.CurrentUser

class AsyncDeployment extends CometActor with CometListener with Loggable {
  
  private[this] val periodFormatter = new PeriodFormatterBuilder().
    appendDays().
    appendSuffix(" day ", " days ").
    appendSeparator(", ").
    appendMinutes().
    appendSuffix(" min ", " min ").
    appendSeparator(" ").
    appendSeconds().
    appendSuffix("s", "s")
    .toFormatter()
  
  private[this] def formatPeriod(duration:Duration) : String = {
    if(duration.getMillis < 1000) "less than 1s"
    else periodFormatter.print(duration.toPeriod)
  }
  
  private[this] val asyncDeploymentAgent = inject[AsyncDeploymentAgent]

  //current states of the deployment
  private[this] var deploymentStatus = DeploymentStatus(NoStatus, IdleDeployer)
  
  override def registerWith = asyncDeploymentAgent
  
  override def lowPriority = {
    case d:DeploymentStatus => deploymentStatus = d ; reRender()
  }
  
  override def render = {
    new RenderOut(( 
      ClearClearable &
      "#deploymentLastStatus *" #> lastStatus &
      "#deploymentProcessing *" #> currentStatus
    )(layout) , JsRaw("""$("button.deploymentButton").button();  """))
  }
  
  private[this] def lastStatus : NodeSeq = {
    deploymentStatus.current match {
      case NoStatus => <span>Configuration rules application status unavailable</span>
      case SuccessStatus(id,start,end,configurationNodes) => 
        <span class="deploymentSuccess">
          <img src="/images/icOK.png" alt="Error" height="16" width="16" class="iconscala" />
          Success: configuration rules applied at {DateFormaterService.getFormatedDate(start)} (took {formatPeriod(new Duration(start,end))})
        </span>
      case ErrorStatus(id,start,end,failure) => 
        {<span class="error deploymentError"><img src="/images/icfail.png" alt="Error" height="16" width="16" class="iconscala" />
          Error: configuration rules not applied at {DateFormaterService.getFormatedDate(start)} <br/>(took {formatPeriod(new Duration(start,end))} -
          <span class="errorscala" id="errorDetailsLink" onClick={
            """$('#errorDetailsDialog').modal({ minHeight:140, minWidth: 300 }); 
               $('#simplemodal-container').css('height', 'auto');
               return false;"""}>details</span>) 
        </span>} ++ {
          ("#errorDetailsMessage" #> <span>{failure.messageChain.split("<-").map(x => Text("=> " + x) ++ {<br/>})}</span>)(errorPopup)
        }
    }
  }
  
  private[this] def currentStatus : NodeSeq = {
    deploymentStatus.processing match {
      case IdleDeployer =>
        SHtml.ajaxButton("Regenerate now", { () => 
          asyncDeploymentAgent ! StartDeployment(CurrentUser.getActor)
          Noop
        }, ( "class" , "deploymentButton")) 
      case Processing(id, start) =>
        <span>
          <img src="/images/deploying.gif" alt="Deploying..." height="16" width="16" class="iconscala" />
          Generating configuration rules (started at {DateFormaterService.getFormatedDate(start)})
        </span>
      case ProcessingAndPending(asked, Processing(id, start)) => 
        <span>
          <img src="/images/deploying.gif" alt="Deploying..." height="16" width="16" class="iconscala" />
          Generating configuration rules (started at {DateFormaterService.getFormatedDate(start)}). Another generation is pending since {DateFormaterService.getFormatedDate(asked)}
        </span>
    }
  }
  
  private[this] def layout = {
    <div id="deploymentStatus">
      <lift:ignore>
        Here come the status of the last finised deployment.
        Status can be: no previous deployment, correctly deployed, warning, error.
      </lift:ignore>
      <div id="deploymentLastStatus">
        [Here comes the status of the last finished deployement]
      </div>

      <lift:ignore>
        Here comes an indication of the current deployement.
        May be : not deploying (a button is shown to start a deployment), deploying (give an idea of the time remaining ?), deploying + one pending
      </lift:ignore>
      <div id="deploymentProcessing">
        [Here comes the current deployment processing]
      </div>
    </div>
  }
  
  private[this] def errorPopup = {
    <div id="errorDetailsDialog" class=".not">
      <div class="simplemodal-title">
        <h1>Error</h1>
        <hr/>
      </div>
      <div class="simplemodal-content">
       <br />
        <div>
          <img src="/images/icfail.png" alt="Error" height="24" width="24" class="erroricon" />
          <h2>Deployment process was stopped due to an error:</h2>
        </div>        
        <hr class="spacer" />
        <br />
        <p id="errorDetailsMessage">[Here come the error message]</p>
        <hr class="spacer" />
        <br />
        <br />
        <div align="right">
          <button class="ui-button ui-widget ui-state-default ui-corner-all ui-button-text-only simplemodal-close" onClick="return false;">
            <span class="ui-button-text">Close</span>
          </button>
        <br />
        </div>
      </div>
    </div>
  }
  
}
