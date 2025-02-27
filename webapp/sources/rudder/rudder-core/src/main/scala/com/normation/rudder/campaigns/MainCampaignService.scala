/*
*************************************************************************************
* Copyright 2022 Normation SAS
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

package com.normation.rudder.campaigns

import cats.implicits._
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.rudder.campaigns.CampaignEventState._
import com.normation.utils.StringUuidGenerator
import com.normation.zio.ZioRuntime
import org.joda.time.DateTime
import zio.Queue
import zio.ZIO
import zio.clock.Clock
import zio.duration._
import zio.syntax._


trait CampaignHandler{
  def handle(mainCampaignService: MainCampaignService, event :CampaignEvent): PartialFunction[Campaign, IOResult[CampaignEvent]]
}

object MainCampaignService {
  def start(mainCampaignService: MainCampaignService) = {
    for {
      campaignQueue <- Queue.unbounded[CampaignEvent]
      _ <- mainCampaignService.start(campaignQueue).forkDaemon
    } yield ()
  }
}

class MainCampaignService(repo: CampaignEventRepository, campaignRepo: CampaignRepository, uuidGen: StringUuidGenerator) {

  private[this] var services : List[CampaignHandler] = Nil
  def registerService(s : CampaignHandler) = {
    services = s :: services
  }

  private[this] var inner : Option[CampaignScheduler] = None


  def queueCampaign(c: CampaignEvent) = {
    inner match {
      case Some(s) => s.queueCampaign(c)
      case None => Inconsistency("not initialized yet").fail
    }
  }

  case class CampaignScheduler(main : MainCampaignService, queue:Queue[CampaignEvent], zclock: Clock) {

    def queueCampaign(c: CampaignEvent) = {
      for {
        _ <- queue.offer(c)
      } yield {
        c
      }
    }

    def handle(event: CampaignEvent) = {

      def base: PartialFunction[Campaign, IOResult[CampaignEvent]] = {
        case _ => event.succeed
      }

      val now = DateTime.now()

      (for {
        campaign <- campaignRepo.get(event.campaignId)
        wait <-
          event.state match {
            case Finished =>
              ().succeed
            case Scheduled | Skipped =>
              if (event.start.isAfter(now)) {
                for {
                  _ <- ZIO.sleep(Duration.fromMillis(event.start.getMillis - now.getMillis))
                } yield {
                  ()
                }
              } else
                ().succeed
            case Running =>
              if (event.end.isAfter(now)) {
                for {
                  _ <- ZIO.sleep(Duration.fromMillis(event.end.getMillis - now.getMillis))
                } yield {
                  ()
                }
              } else ().succeed
          }
        updatedEvent <- repo.get(event.id).catchAll(_ => event.succeed)
        handle = services.map(_.handle(main,updatedEvent)).foldLeft(base) { case (base, handler) => handler orElse base }
        newCampaign <- handle.apply(campaign).catchAll(
          err =>
          for {
            _ <- CampaignLogger.error(err.fullMsg)
          } yield  {
            event
          }
        )
        save <- repo.saveCampaignEvent(newCampaign)
        post <-
          newCampaign.state match {
            case Finished =>
              for {
                campaign <- campaignRepo.get(event.campaignId)
                up <-  scheduleCampaignEvent(campaign)
              } yield {
                up
              }
            case Scheduled|Running|Skipped =>
              for {
                _ <- repo.saveCampaignEvent(newCampaign)
                _ <- queueCampaign(newCampaign)
              } yield {
                ()
              }
          }
      } yield {
        ()
      }).provide(zclock).forkDaemon


    }

    def loop() = {
      for {
        c <- queue.take
        _ <- handle(c)
      } yield {
        ()
      }
    }



    def start() = {
      for {
        init <- repo.getWithCriteria(Running :: Scheduled :: Nil, None, None)
        _ <- queue.offerAll(init)
        _ <- loop().forever.forkDaemon
      } yield {
        ()
      }

    }
  }

  def nextCampaignDate(schedule : CampaignSchedule, date : DateTime) : IOResult[DateTime] = {
    schedule match {
      case OneShot(s) =>
        if (s.isAfter(date)) {
          s.succeed
        } else {
          Inconsistency("Cannot schedule").fail
        }
      case WeeklySchedule(day,startHour, startMinute) =>
        val realHour = startHour % 24
        val realMinutes = startMinute % 60
        for {
          d <- (if ( date.getDayOfWeek > day.value
                || ( date.getDayOfWeek == day.value && date.getHourOfDay >= realHour)
                || ( date.getDayOfWeek == day.value && date.getHourOfDay == realHour && date.getMinuteOfHour >= realMinutes)
                ) {
                  date.plusWeeks(1)
                } else {
                  date
                }).withDayOfWeek(day.value).withHourOfDay(realHour).withMinuteOfHour(realMinutes).withSecondOfMinute(0).withMillisOfSecond(0).succeed
        } yield {
          d
        }
      case MonthlySchedule(position, day, startHour, startMinute) =>
        val realHour = startHour % 24
        val realMinutes = startMinute % 60
        val d = (position match {
          case First =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear < date.getMonthOfYear) {
              t.plusWeeks(1)
            } else {
              t
            }
          case Second =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear < date.getMonthOfYear) {
              t.plusWeeks(2)
            } else {
              t.plusWeeks(1)
            }
          case Third =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear < date.getMonthOfYear) {
              t.plusWeeks(3)
            } else {
              t.plusWeeks(2)
            }
          case Last =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear > date.getMonthOfYear) {
              t.minusWeeks(1)
            } else {
              t
            }
          case SecondLast =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear > date.getMonthOfYear) {
              t.minusWeeks(2)
            } else {
              t.minusWeeks(1)
            }
        }).withHourOfDay(realHour).withMinuteOfHour(realMinutes).withSecondOfMinute(0).withMillisOfSecond(0)
        (if (date.isAfter(d)) {
          d.plusMonths(1)
        } else {
          d
        }).plusMonths(1).succeed
    }
  }

  def scheduleCampaignEvent(campaign: Campaign) : IOResult[CampaignEvent] = {

    for {
      events <- repo.getWithCriteria(Scheduled :: Running :: Nil, None,Some(campaign.info.id))
      lastEventDate = events match {
        case Nil => DateTime.now()
        case _ => events.maxBy(_.start.getMillis).start
      }
      newEventDate <- nextCampaignDate(campaign.info.schedule, lastEventDate)
      end = newEventDate.plus(campaign.info.duration.toMillis)
      newCampaign = CampaignEvent(CampaignEventId(uuidGen.newUuid),campaign.info.id,Scheduled,newEventDate,end,campaign.campaignType)
      _ <- repo.saveCampaignEvent(newCampaign)
      _ <- queueCampaign(newCampaign)
    } yield {
      newCampaign
    }
  }


  def init() = {
    inner match {
      case None => Inconsistency("Campaign queue not initialized. campaign service was not started accordingly").fail
      case Some(s) =>
        for {
          alreadyScheduled <- repo.getWithCriteria(Running :: Scheduled :: Nil, None, None)
          campaigns <- campaignRepo.getAll()
          _ <- CampaignLogger.debug(s"Got ${campaigns.size} campaigns, check all started")
          newEvents <- ZIO.foreach(campaigns.filterNot(c => alreadyScheduled.exists(_.campaignId == c.info.id))) {
            c =>
              scheduleCampaignEvent(c)
          }
          _ <- CampaignLogger.debug(s"Scheduled ${newEvents.size} new events, queue them")
          _ <- ZIO.foreach(newEvents) { ev => s.queueCampaign(ev) }
        } yield {
          ()
        }
    }
  }
  def start(initQueue: Queue[CampaignEvent]) = {
    val s = CampaignScheduler(this, initQueue, ZioRuntime.environment)
    inner = Some(s)
    for {
      _ <- CampaignLogger.debug("Starting campaign scheduler")
      _ <- s.start().forkDaemon

      _ <- CampaignLogger.debug("Starting campaign forked, now getting already created events")
      alreadyScheduled <- repo.getWithCriteria(Running :: Scheduled :: Nil, None, None)
      _ <- CampaignLogger.debug("Got events, queue them")
      _ <- ZIO.foreach(alreadyScheduled) { ev => s.queueCampaign(ev) }
      _ <- CampaignLogger.debug("queued events, check campaigns")
      _ <- init()

    } yield {
      ()
    }
  }

}


