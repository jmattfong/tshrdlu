package tshrdlu.project

import twitter4j._
import tshrdlu.twitter._
import tshrdlu.util.{English,SimpleTokenizer}

/**
 * Show only tweets that appear to be English.
 */
object EnglishStatusStreamer extends BaseStreamer with EnglishStatusListener

/**
 * Debug the English detector.
 */
object EnglishStatusStreamerDebug extends BaseStreamer with EnglishStatusListenerDebug

/**
 * Output only tweets detected as English.
 */
trait EnglishStatusListener extends StatusListenerAdaptor {

  /**
   * If a status' text is English, print it.
   */
  override def onStatus(status: Status) { 
    val text = status.getText
    if (isEnglish(text)) 
      println(text)
  }

  /**
   * Test whether a given text is written in English.
   */
  val TheRE = """(?i)\bthe\b""".r // Throw me away!
  val WhitespaceRE = """\s+""".r
  val HashtagRE = """#[A-Za-z]+.*""".r
  val MentionRE = """@[A-Za-z]+.*""".r
  val WebsiteRE = """\b.*[A-Za-z0-9_\-]+\.[A-Za-z]+.*\b""".r //simple website checker
  val WordRE = """[^A-Za-z]*([A-Za-z]+((\-|')[A-Za-z]+)?)[^A-Za-z]*""".r
  def isEnglish(text: String) = {
    val words = getActualWords(text)
    val numWords = words.size.toDouble
    val numEnglishWords = words.count(English.vocabulary.contains(_))
    val percentEnglish = numEnglishWords / numWords
    percentEnglish > 0.5
  }
  
  def getActualWords(text: String) = WhitespaceRE.split(text)
    .filter(x => HashtagRE.findFirstIn(x) == None)
    .filter(x => MentionRE.findFirstIn(x) == None)
    .filter(x => WebsiteRE.findFirstIn(x) == None)
    .flatMap(x => WordRE.findFirstMatchIn(x))
    .map(x => x.group(1).toLowerCase).toList

}

/**
 * Output both English and non-English tweets in order to improve
 * the isEnglish method in EnglishStatusListener.
 */
trait EnglishStatusListenerDebug extends EnglishStatusListener {

  /**
   * For each status, print it's text, prefixed with the label
   * [ENGLISH] for those detected as English and [OTHER] for
   * those that are not.
   */
  override def onStatus(status: Status) { 
    val text = status.getText
    val prefix = if (isEnglish(text)) "[ENGLISH]" else "[OTHER]  "
    println(prefix + " " + text)
  }
}


/**
 * Output polarity labels for every English tweet and output polarity
 * statistics at default interval (every 100 tweets). Done for 
 * tweets from the Twitter sample.
 */
object PolarityStatusStreamer extends BaseStreamer with PolarityStatusListener

/**
 * Output polarity labels for every English tweet and output polarity
 * statistics at default interval (every 100 tweets). Filtered by provided
 * query terms.
 */
object PolarityTermStreamer extends FilteredStreamer with PolarityStatusListener with TermFilter

/**
 * Output polarity labels for every English tweet and output polarity
 * statistics at an interval of every ten tweets. Filtered by provided
 * query locations.
 */
object PolarityLocationStreamer extends FilteredStreamer with PolarityStatusListener with LocationFilter {
  override val outputInterval = 10
}


/**
 * For every tweet detected as English, compute its polarity based
 * on presence of positive and negative words, and output the label
 * with the tweet. Output statistics about polarity at specified
 * intervals (as given by the outputInterval variable).
 */
trait PolarityStatusListener extends EnglishStatusListener {

  import tshrdlu.util.DecimalToPercent

  val outputInterval = 100

  var numEnglishTweets = 0

  // Indices: 0 => +, 1 => -, 2 => ~
  val polaritySymbol = Array("+","-","~")
  var polarityCounts = Array(0.0,0.0,0.0)

  override def onStatus(status: Status) {
    val text = status.getText
    if (isEnglish(text)) {
      val polarityIndex = getPolarity(text)
      polarityCounts(polarityIndex) += 1

      numEnglishTweets += 1

      println(polaritySymbol(polarityIndex) + ": " + text)

      if ((numEnglishTweets % outputInterval) == 0) {
	println("----------------------------------------")
	println("Number of English tweets processed: " + numEnglishTweets)
	println(polaritySymbol.mkString("\t"))
	println(polarityCounts.mkString("\t"))
	println(polarityCounts.map(_/numEnglishTweets).map(DecimalToPercent).mkString("\t"))
	println("----------------------------------------")
      }
    }
    
  }
  
  val positiveWords : Set[String] = English.getLexicon("positive-words.txt.gz")
  val negativeWords : Set[String] = English.getLexicon("negative-words.txt.gz")

  /**
   * Given a text, return its polarity:
   *   0 for positive
   *   1 for negative
   *   2 for neutral
   */
  val random = new scala.util.Random
  def getPolarity(text: String) = {
    val words = getActualWords(text)
    val numPositive = words.count(positiveWords.contains(_))
    val numNegative = words.count(negativeWords.contains(_))
    if(numPositive > numNegative) 0 else if (numPositive < numNegative) 1 else 2
  }

}
