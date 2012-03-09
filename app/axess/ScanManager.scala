package axess

import akka.actor._
import akka.pattern._
import akka.util.duration._
import akka.util.Timeout
import akka.routing.RoundRobinRouter
import akka.routing.Broadcast
import models.ScanMsg

class ScanManager extends Actor {

  val NUM_WORKERS = 10
  val workers = context.actorOf(Props[Worker].withRouter(RoundRobinRouter(NUM_WORKERS)),
    name = "workerRouter")

  override def postStop = curScan match {
    case None => true
    case Some(site) => context.parent ! ScanError(site)
  }

  /** Current site we're scanning. */
  var curScan: Option[ScanSite] = None
  /** Pages we have seen, but not yet scanned. */
  val pagesEncountered = scala.collection.mutable.Set[String]()
  /** Pages we have already scanned. */
  val pagesScanned = scala.collection.mutable.Set[String]()

  def sendDone() = curScan match {
    case None => throw new RuntimeException("oops")
    case Some(scan) => {
      if (pagesEncountered.size == pagesScanned.size && pagesScanned.size > 0) {
        context.parent ! ScanComplete(scan.scanId)
      }
    }
  }

  def scanSite(s: ScanSite) = curScan match {
    case None => // normal
      // Set up internal state
      curScan = Some(s)
      pagesEncountered.clear()
      pagesScanned.clear()
      // Set up child state
      workers ! Broadcast(NewSite(s.site)) // Clear cookies
      // Start
      workers ! ScanPage(s.site.home) // Start the process

    case Some(_) => sender ! ManagerBusy(s)
  }

  def pageResult(r: PageScanResult) = curScan match {
    case None => throw new RuntimeException("oops")
    case Some(site) =>
      pagesScanned += r.url
      for (pageNote <- r.notes) {
        ScanMsg.addMsg(site.scanId, pageNote.category, r.url, pageNote.msg)
      }
      sendDone()
  }

  def newUrls(n: NewUrls) = curScan match {
    case None => throw new RuntimeException("oops")
    case Some(site) =>
      for {
        url <- n.urls
        if !pagesEncountered.contains(url)
      } {
        pagesEncountered += url
        workers ! ScanPage(url)
      }
      sendDone()
  }

  def receive = {
    case s: ScanSite => scanSite(s)
    case r: PageScanResult => pageResult(r)
    case n: NewUrls => newUrls(n)
  }
}
