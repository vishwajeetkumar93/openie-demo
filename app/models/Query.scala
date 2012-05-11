package models

import edu.washington.cs.knowitall.browser.extraction.Instance
import edu.washington.cs.knowitall.browser.lucene.ParallelExtractionGroupFetcher
import edu.washington.cs.knowitall.browser.extraction.ExtractionGroup
import edu.washington.cs.knowitall.browser.extraction.ReVerbExtraction

import java.util.regex.Pattern

object Query {
  type REG = ExtractionGroup[ReVerbExtraction]

  val paths = Seq("/scratch/common/openie-demo/test-index",
    "/scratch2/common/openie-demo/test-index",
    "/scratch3/common/openie-demo/test-index",
    "/scratch4/common/openie-demo/test-index")
  val fetcher = new ParallelExtractionGroupFetcher(paths)
}

case class Query(
  arg1: Option[String],
  rel: Option[String],
  arg2: Option[String]) {

  import Query._

  require(arg1.isDefined ||
    rel.isDefined ||
    arg2.isDefined,
    "At least one relation part must be specified.")

  def this(arg1: String, rel: String, arg2: String) =
    this(Some(arg1), Some(rel), Some(arg2))

  def arg1String = arg1.getOrElse("")
  def relString = rel.getOrElse("")
  def arg2String = arg2.getOrElse("")

  def execute() = {
    def part(eg: REG, part: Symbol) = part match {
      case 'rel => GroupTitlePart(eg.relNorm, eg.instances.map(_.extraction.source.getRelation.getText).toSeq, None, Seq.empty)
      case 'arg1 => GroupTitlePart(eg.arg1Norm, eg.instances.map(_.extraction.source.getArgument1.getText).toSeq, eg.arg1Entity, eg.arg1Types)
      case 'arg2 => GroupTitlePart(eg.arg2Norm, eg.instances.map(_.extraction.source.getArgument2.getText).toSeq, eg.arg2Entity, eg.arg2Types)
    }

    def group: REG=>GroupTitle = (this.arg1, this.rel, this.arg2) match {
      case (Some(arg1), None, None) => (eg: REG) => GroupTitle(" ", Seq(part(eg, 'rel), part(eg, 'arg2)))
      case (None, Some(rel), None) => (eg: REG) => GroupTitle(", ", Seq(part(eg, 'arg1), part(eg, 'arg2)))
      case (None, None, Some(arg2)) => (eg: REG) => GroupTitle(", ", Seq(part(eg, 'arg1), part(eg, 'rel)))

      case (Some(arg1), Some(rel), None) => (eg: REG) => GroupTitle("", Seq(part(eg, 'arg2)))
      case (None, Some(rel), Some(arg2)) => (eg: REG) => GroupTitle("", Seq(part(eg, 'arg1)))
      case (Some(arg1), None, Some(arg2)) => (eg: REG) => GroupTitle("", Seq(part(eg, 'rel)))

      case (Some(arg1), Some(rel), Some(arg2)) => (eg: REG) => GroupTitle("", Seq(part(eg, 'arg2)))
      case (None, None, None) => (eg: REG) => GroupTitle(" ", Seq(part(eg, 'arg1), part(eg, 'rel), part(eg, 'arg2)))
    }

    val results = Query.fetcher.getGroups(this.arg1, this.rel, this.arg2).map { reg =>
      reg.copy(instances = reg.instances filter filterInstances)
    }
    val groups = Group.fromExtractionGroups(results, group)

    groups
  }


  private final val pronouns: Set[String] = Set("he", "she", "they", "them",
     "that", "this", "who", "whom", "i", "you", "him", "her", "we",
     "it", "the", "a", "an")
  private final val nonQuestionableChars = Pattern.compile("[\\p{Lower}\\p{Digit} ]+")
  private final val stripExtraWS = Pattern.compile(" +")
  private final val stripChars= Pattern.compile("[^\\p{Graph} ]+")
  private final val leadingBadChars = Pattern.compile("^\\s*(\\\"|\\'|\\()\\s")
  private final val leadingArticle = Pattern.compile("^\\s*(the|this|these|those|that|a|an)\\s", Pattern.CASE_INSENSITIVE)
  private final val startCap = Pattern.compile(".*\\b[A-Z].*")
  private final val likelyError = Pattern.compile(".*(http|\\(|\\)|\\\"|\\[|thing).*", Pattern.CASE_INSENSITIVE)
  def filterInstances(inst: Instance[ReVerbExtraction]): Boolean = {
    def clean(arg: String) = {
      var clean = arg

      clean = stripChars.matcher(clean).replaceAll("");
      clean = stripExtraWS.matcher(clean).replaceAll(" ").trim();
      clean = leadingBadChars.matcher(clean).replaceAll("");
      clean = leadingArticle.matcher(clean).replaceAll("");

      clean.toLowerCase
    }

    val arg1clean = clean(inst.extraction.source.getArgument1.getTokensAsString)
    val arg2clean = clean(inst.extraction.source.getArgument2.getTokensAsString)
    val relclean = clean(inst.extraction.source.getRelation.getTokensAsString)

    if (arg1clean.length + relclean.length + arg2clean.length > 120) {
      false
    }
    else if (pronouns.contains(arg1clean) || pronouns.contains(arg2clean)) {
      false
    }
    else if (arg1clean.isEmpty || relclean.isEmpty || arg2clean.isEmpty) {
      false
    }
    else {
      true
    }
  }
}
