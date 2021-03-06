package controllers

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import edu.knowitall.common.Resource.using
import edu.knowitall.common.Timing
import edu.knowitall.openie.models.Extraction
import edu.knowitall.openie.models.ExtractionGroupProtocol.listFormat
import edu.knowitall.openie.models.FreeBaseType
import edu.knowitall.openie.models.Instance
import edu.knowitall.openie.models.InstanceProtocol.InstanceFormat
import edu.knowitall.openie.models.serialize.Chill
import models.AnswerSet
import models.LogEntry
import models.NegativeTypeFilter
import models.PositiveTypeFilter
import models.Query
import models.TypeFilter
import models.TypeFilters
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.optional
import play.api.data.Forms.text
import play.api.mvc.Action
import play.api.mvc.Result
import play.api.mvc.Controller
import play.api.mvc.RequestHeader
import sjson.json.JsonSerialization.tojson
import edu.knowitall.openie.models.ExtractionGroupProtocol
import edu.knowitall.openie.models.InstanceProtocol
import play.api.templates.Html
import controllers.Executor.ExecutionSettings

object Application extends Controller {
  final val PAGE_SIZE = 20
  final val MAX_SENTENCE_COUNT = 15

  Logger.info("Server started.")

  /**
    * The actual definition of the search form.
    */
  def searchForm: Form[Query] = {
    def unapply(query: Query): Option[(Option[String], Option[String], Option[String], Option[String])] = {
      Some(query.arg1.map(_.toString), query.rel.map(_.toString), query.arg2.map(_.toString), query.corpora.map(_.toString))
    }
    Form(
    // Defines a mapping that will handle Contact values
      (mapping (
        "arg1" -> optional(text),
        "rel" -> optional(text),
        "arg2" -> optional(text),
        "corpora" -> optional(text)
      )(Query.fromStrings)(unapply)).verifying("All search fields cannot be empty", { query =>
        query.arg1.isDefined || query.rel.isDefined || query.arg2.isDefined
      })
    )
  }

  def footer(reload: Boolean = false): String = {
    def loadFooter =
      try {
        val footerFile = new java.io.File("/cse/www2/knowitall/footer.html")
        val footer =
          using (scala.io.Source.fromFile(footerFile)) { file =>
            file.mkString
          }
        Cache.set("footer", footer)
        footer
      } catch {
        case e: Exception => Logger.error("Exception loading footer." + e); ""
      }

    if (reload) {
      loadFooter
    }
    else {
      Cache.getAs[String]("footer").getOrElse(loadFooter)
    }
  }

  /**
    * This is the index page that hold the search form.
    */
  def index(reloadFooter: Boolean) = Action {
    Ok(views.html.index(searchForm, footer(reloadFooter)))
  }

  private def settingsFromRequest(debug: Boolean, request: play.api.mvc.Request[play.api.mvc.AnyContent]) = {
    var settings = Executor.ExecutionSettings.default
    if (debug) {
      val entityThresh: Option[Double] = request.queryString.get("entityThresh").flatMap(_.headOption.map(_.toDouble))

      entityThresh.foreach(thresh => settings = settings.copy(entityScoreThreshold = thresh))
    }
    settings
  }

  /**
    * Handle POST requests to search.
    */
  def submit(debug: Boolean = false) = Action { implicit request =>

    searchForm.bindFromRequest.fold(
      errors => BadRequest(views.html.index(errors, footer())),
      query => {
        val answers = scala.concurrent.future {
          searchGroups(query, settingsFromRequest(debug, request), debug)
        }

        Async {
          answers.map { case (answers, message) =>
              val filtered = setupFilters(query, answers, "all", 0)._2

              //choose a cut-off to filter out the entities that have few
              //results, and only display to a max of 7 entities
              val ambiguousEntities = filtered.queryEntities.zipWithIndex.filter{
                case ((fbe, entityCount), index)  => index < 7 && entityCount > 5
              }

              if(ambiguousEntities.size == 0){
                //when there is no entity that satisfy the cut-off filter above
                //i.e, when results number is too small, do the regular query search.
                doSearch(query, "", "all", 0, settingsFromRequest(debug, request), debug=debug)
              }else if(ambiguousEntities.size == 1){
                //when there is only a single entity present after the filter
                //go directly to the linked entity query search
                val entityName = ambiguousEntities(0)._1._1.name
                query.arg2.map(_.toString) match {
                  case Some(x) => doSearch(Query.fromStrings(query.arg1.map(_.toString), query.rel.map(_.toString), Option("entity:" + entityName), query.corpora.map(_.corpora)), query.arg2.map(_.toString).get, "all", 0, settingsFromRequest(debug, request), debug=debug)
                  case None => doSearch(Query.fromStrings(Option("entity:" + entityName), query.rel.map(_.toString), query.arg2.map(_.toString), query.corpora.map(_.corpora)), query.arg1.map(_.toString).get, "all", 0, settingsFromRequest(debug, request), debug=debug)
                }
              }else{
                //if there are more than 1 entities that are ambiguous
                //direct to the disambiguation page and display an query-card for each
                disambiguate(query, settingsFromRequest(debug, request), debug=debug)
              }
          }
        }
      }
    )
  }

  /**
   * Do the filtering of answers according to the query, answerSet and filterString.
   *
   *
   * @return a tuple of (filters, filtered results, and single page of filtered results)
   */
  private def setupFilters(query: Query, answers: AnswerSet, filterString: String, pageNumber : Int) = {
      val filters: Set[TypeFilter] = filterString match {
        case "" | "all" => Set()
        case "misc" => answers.filters.map(_.filter).collect { case filter: PositiveTypeFilter => filter } .map(filter => NegativeTypeFilter(filter.typ, query.freeParts)).toSet
        case s => Set(PositiveTypeFilter(FreeBaseType.parse(s).getOrElse(throw new IllegalArgumentException("invalid type string: " + s)), query.freeParts))
      }

      val filtered = answers filter filters
      Logger.info(query + " with " + filters + " has " + filtered.answerCount + " answers " + filtered.sentenceCount + " results")
      val page = filtered.page(pageNumber, PAGE_SIZE)

      (filters, filtered, page)
  }

  def search(arg1: Option[String], rel: Option[String], arg2: Option[String], filter: String, page: Int, debug: Boolean, log: Boolean, corpora: Option[String]) = Action { implicit request =>
    doSearch(Query.fromStrings(arg1, rel, arg2, corpora), "", filter, page, settingsFromRequest(debug, request), debug=debug, log=log)
  }

  def json(arg1: Option[String], rel: Option[String], arg2: Option[String], count: Int, corpora: Option[String]) = Action {
    val query = Query.fromStrings(arg1, rel, arg2, corpora)
    Logger.info("Json request: " + query)

    import ExtractionGroupProtocol._
    Ok(tojson(Executor.executeRaw(query.toLowerCase).take(count)).toString.replaceAll("[\\p{C}]",""))
  }

  def instancesJson() = Action { implicit request =>
    Ok(Html("""<html><head><title>Instance Deserializer</title></head><body><h1>Instance Deserializer</h1><form method="POST"><textarea cols="80" rows="20" name="base64"></textarea><br /><input type="submit" /></body></html>"""))
  }

  case class InstanceInput(base64: String)
  val instanceForm = Form((mapping("base64" -> text)(InstanceInput.apply)(InstanceInput.unapply)))
  def instancesJsonSubmit() = Action { implicit request =>
    val input = instanceForm.bindFromRequest().get
    val base64 = input.base64

    import InstanceProtocol._
    val kryo = Chill.createBijection()
    val bytes = Base64.decodeBase64(base64)
    val instances = kryo.invert(bytes).asInstanceOf[List[Instance[Extraction]]]
    Ok(tojson(instances.head).toString.replaceAll("[\\p{C}]",""))
  }

  def sentences(arg1: Option[String], rel: Option[String], arg2: Option[String], title: String, debug: Boolean, corpora: Option[String]) = Action {
    val query = Query.fromStrings(arg1, rel, arg2, corpora)
    Logger.info("Sentences request for title '" + title + "' in: " + query)
    val group = searchGroups(query, ExecutionSettings.default, debug)._1.answers.find(_.title.text == title) match {
      case None => throw new IllegalArgumentException("could not find group title: " + title)
      case Some(group) => group
    }

    Ok(views.html.sentences(group, debug))
  }

  def logsFromDate(date: DateTime = DateTime.now) =
    logs(date.getYear, date.getMonthOfYear, date.getDayOfMonth)

  def logs(year: Int, month: Int, day: Int) = Action {
    val today = new DateTime(year, month, day, 0, 0, 0, 0)

    Ok(views.html.logs(LogEntry.logs(year, month, day), today))
  }

  def searchGroups(query: Query, settings: ExecutionSettings, debug: Boolean) = {
    Logger.debug("incoming " + query)
    Cache.getAs[AnswerSet](query.toString.toLowerCase) match {
      case Some(answers) if !debug =>
        Logger.debug("retrieving " + query + " from cache")

        val AnswerSet(groups, filters, entities) = answers

        // cache hit
        Logger.info(query.toString +
          " retrieved from cache" +
          " with " + groups.size + " answers" +
          " and " + groups.iterator.map(_.contents.size).sum + " results")
        (answers, Some("cached"))
      case _ =>
        Logger.debug("executing " + query + " in lucene")

        // cache miss
        val (ns, result) = Timing.time(Executor.execute(query.toLowerCase, settings))

        val (groups, message) = result match {
          case Executor.Success(groups) => (groups, None)
          case Executor.Timeout(groups) => (groups, Some("timeout"))
          case Executor.Limited(groups) => (groups, Some("results truncated"))
        }

        val answers = AnswerSet.from(query, groups, TypeFilters.fromGroups(query, groups, debug))

        Logger.info(query.toString +
          " executed in " + Timing.Seconds.format(ns) +
          " with " + groups.size + " answers" +
          " and " + groups.iterator.map(_.contents.size).sum + " sentences" + message.map(" (" + _ + ")").getOrElse(""))

        // cache unless we had a timeout
        if (!result.isInstanceOf[Executor.Timeout[_]]) {
          Logger.debug("Saving " + query.toString + " to cache.")
          Cache.set(query.toString.toLowerCase, answers, 60 * 10)
        }

        (answers, message)
    }
  }

  def results(arg1: Option[String], rel: Option[String], arg2: Option[String], filterString: String, pageNumber: Int, justResults: Boolean, debug: Boolean = false, corpora: Option[String]) = Action { implicit request =>
    doSearch(Query.fromStrings(arg1, rel, arg2, corpora), "", filterString, pageNumber, settingsFromRequest(debug, request), debug=debug, log=true, justResults=justResults)
  }

  /**
   * @param query  arg1, relation, arg2 and corpora being searched for
   * @param queryString  the user input string for arg1 or arg2 when page is directed to entity linked results page
   * @param filterString  the filter string for different categories
   * @param pageNumber  the number of page that is being displayed on results page
   * @param settings  the execution settings from request
   * @param debug  display the page in debug mode when true, which shows solr query and freeBaseEntities
   * @param log  log the query 
   * @param justResults  only refresh the results content of the results page when true, keep the query card unchanged
   */
  def doSearch(query: Query, queryString: String, filterString: String, pageNumber: Int, settings: ExecutionSettings, debug: Boolean = false, log: Boolean = true, justResults: Boolean = false)(implicit request: RequestHeader) = {
    Logger.info("Search request: " + query)

    val maxQueryTime = 20 * 1000 /* ms */

    val answers = scala.concurrent.future {
      searchGroups(query, settings, debug)
    }

    Async {
      answers.map { case (answers, message) =>
        val filter = setupFilters(query, answers, filterString, pageNumber)

        if (log) {
          LogEntry.fromRequest(query, filterString, answers.answerCount, answers.sentenceCount, request).log()
        }

        //if only the category of results is clicked, change the page's result content
        //else generate a header with the result content
        if (justResults) {
          Ok(views.html.results(query, filter._3, filter._1.toSet, filterString, pageNumber, math.ceil(filter._2.answerCount.toDouble / PAGE_SIZE.toDouble).toInt, MAX_SENTENCE_COUNT, debug))
        } else {
          //Do not show the redirected info when the user input query is linked
          if(queryString.startsWith("entity:")) {
            Ok(
              views.html.frame.resultsframe(
               searchForm, query, message, filter._3, filter._2.answerCount, filter._2.sentenceCount, "", filterString, true)(
                 views.html.results(query, filter._3, filter._1.toSet, filterString, pageNumber, math.ceil(filter._2.answerCount.toDouble / PAGE_SIZE.toDouble).toInt, MAX_SENTENCE_COUNT, debug)))
          } else {
            Ok(
              views.html.frame.resultsframe(
               searchForm, query, message, filter._3, filter._2.answerCount, filter._2.sentenceCount, queryString, filterString, true)(
                 views.html.results(query, filter._3, filter._1.toSet, filterString, pageNumber, math.ceil(filter._2.answerCount.toDouble / PAGE_SIZE.toDouble).toInt, MAX_SENTENCE_COUNT, debug)))
          }
        }
      }
    }
  }

  def disambiguate(query: Query, settings: ExecutionSettings, debug: Boolean = false, log: Boolean = true)(implicit request: RequestHeader) = {
    val maxQueryTime = 20 * 1000 /* ms */

    val answers = scala.concurrent.future {
      searchGroups(query, settings, debug)
    }

    Async {
      val filterString = "all"

      answers.map { case (answers, message) =>
        val filter = setupFilters(query, answers, filterString, 0)

        if (log) {
          LogEntry.fromRequest(query, filterString, answers.answerCount, answers.sentenceCount, request).log()
        }

        //choose a cut-off to filter out the entities that have few
        //results, and only display to a max of 7 entities
        val ambiguousEntitiesWithEntityCount = filter._2.queryEntities.zipWithIndex.filter{ 
          case ((fbe, entityCount), index)  => index < 7 && entityCount > 5 
        }

        //size of the largest entity count
        val largestEntityCount = ambiguousEntitiesWithEntityCount.size match {
          case 0 => 0
          case _ => ambiguousEntitiesWithEntityCount(1)._1._2
        }
        //ambiguous entities with added filter, filter out results that have less than 10% answers
        //than the largest entity count, and that don't contain the query arg's keywords
        val matchingEntitiesWithEntityCount = largestEntityCount match {
          case 0 => ambiguousEntitiesWithEntityCount
          case _ => query.arg1.map(_.toString).isEmpty match {
            case true => ambiguousEntitiesWithEntityCount.filter{
              case ((fbe, entityCount), index) => entityCount > largestEntityCount/10 || fbe.name.toLowerCase().contains(query.arg2.map(_.toString).get.toLowerCase())
            }
            case false => ambiguousEntitiesWithEntityCount.filter{
              case ((fbe, entityCount), index) => entityCount > largestEntityCount/10 || fbe.name.toLowerCase().contains(query.arg1.map(_.toString).get.toLowerCase())
            }
          }
        }

        //get the ambiguous Entities with their index and answerCount
        val answer = filter._2.answers.flatMap(x => x.queryEntity)
        val ambiguousEntitiesWithAnswerCount = for(((fbe, entityCount), index) <- matchingEntitiesWithEntityCount) yield {
          val answerCount = answer.count(_._1.fbid == fbe.fbid)
          (fbe, answerCount)
        }

        //sort the ambiguous entities according to the answer count in decreasing order.
        val sortedAmbiguousEntitiesWithAnswerCount = ambiguousEntitiesWithAnswerCount.sortBy(-_._2)

        //direct to disambiguate page with a resultsFrame header, and disambiguate
        //query card contents.
        Ok(
          views.html.frame.resultsframe(
            searchForm, query, message, filter._2, filter._2.answerCount, filter._2.sentenceCount, "", filterString, false)(
              views.html.disambiguate(query, sortedAmbiguousEntitiesWithAnswerCount, filter._1.toSet, filterString, 0, math.ceil(filter._2.answerCount.toDouble / PAGE_SIZE.toDouble).toInt, MAX_SENTENCE_COUNT, debug)))
      }
    }
  }
}
