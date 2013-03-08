package tshrdlu.twitter

/**
 * Copyright 2013 Jason Baldridge
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import twitter4j._
import collection.JavaConversions._
import tshrdlu.util.{English,SimpleTokenizer}

/**
 * Base trait with properties default for Configuration.
 * Gets a Twitter instance set up and ready to use.
 */
trait TwitterInstance {
  val twitter = new TwitterFactory().getInstance
}

object FollowAnlp extends TwitterInstance with RateChecker {
    def main(args: Array[String]) {
        val screenName = twitter.getScreenName
        val followerIds = twitter.getFollowersIDs("appliednlp",-1).getIDs
        val screenNames = followerIds.flatMap { id => {
            val user = twitter.showUser(id)
            checkAndWait(user)
            if (user.isProtected) None else Some(user.getScreenName)
        }}
        screenNames.foreach { sn => {
            if (sn.endsWith("_anlp") && sn != screenName) twitter.createFriendship(sn)
        }}
    }
}

/**
 * A trait with checkAndWait function that checks whether the
 * rate limit has been hit and wait if it has.
 *
 * This ignores the fact that different request types have different
 * limits, but it keeps things simple.
 */
trait RateChecker {

  /**
   * See whether the rate limit has been hit, and wait until it
   * resets if so. Waits 10 seconds longer than the reset time to
   * ensure the time is sufficient.
   *
   * This is surely not an optimal solution, but it seems to do
   * the trick.
   */
  def checkAndWait(response: TwitterResponse, verbose: Boolean = false) {
    val rateLimitStatus = response.getRateLimitStatus
    if (verbose) println("RLS: " + rateLimitStatus)

    if (rateLimitStatus != null && rateLimitStatus.getRemaining == 0) {
      println("*** You hit your rate limit. ***")
      val waitTime = rateLimitStatus.getSecondsUntilReset + 10
      println("Waiting " + waitTime + " seconds ( "
	      + waitTime/60.0 + " minutes) for rate limit reset.")
      Thread.sleep(waitTime*1000)
    }
  }

}

/**
 * A bot that can monitor the stream and also take actions for the user.
 */
class ReactiveBot extends TwitterInstance with StreamInstance {
  stream.addListener(new UserStatusResponder(twitter))
}

/**
 * Companion object for ReactiveBot with main method.
 */
object ReactiveBot {

  def main(args: Array[String]) {
    val bot = new ReactiveBot
    bot.stream.user
    
    // If you aren't following a lot of users and want to get some
    // tweets to test out, use this instead of bot.stream.user.
    //bot.stream.sample
  }

}


/**
 * A listener that looks for messages to the user and replies using the
 * doActionGetReply method. Actions can be doing things like following,
 * and replies can be generated just about any way you'd like. The base
 * implementation searches for tweets on the API that have overlapping
 * vocabulary and replies with one of those.
 */
class UserStatusResponder(twitter: Twitter) 
extends StatusListenerAdaptor with UserStreamListenerAdaptor {

  import tshrdlu.util.SimpleTokenizer
  import collection.JavaConversions._

  // Thesaurus from Jim Evans and Jason Mielens
  val thesLines = io.Source.fromFile("src/main/resources/dict/en_thes").getLines.toVector
  val thesWords = thesLines.zipWithIndex.filter(!_._1.contains("("))
  val thesList  = thesWords.unzip._1.map(x => x.split("\\|").head)
  val tmpMap = thesWords.map{ w =>
    val lineNum = w._2
    val senses = w._1.split("\\|").tail.head.toInt

    val range = (lineNum + 1) to (lineNum + senses)

    range.map{
      thesLines(_)
    }
  }
  val synonymMap = thesList.zip(tmpMap).toMap.mapValues{ v => v.flatMap{ x=> x.split("\\|").filterNot(_.contains("("))}}.withDefault(x=>Vector(x.toString))

  val username = twitter.getScreenName

  // Recognize a follow command
  lazy val FollowRE = """(?i)(?<=follow)(\s+(me|@[a-z_0-9]+))+""".r

  // Pull just the lead mention from a tweet.
  lazy val StripLeadMentionRE = """(?:)^@[a-z_0-9]+\s(.*)$""".r

  // Pull the RT and mentions from the front of a tweet.
  lazy val StripMentionsRE = """(?:)(?:RT\s)?(?:(?:@[a-z]+\s))+(.*)$""".r   
  override def onStatus(status: Status) {
    println("New status: " + status.getText)
    val replyName = status.getInReplyToScreenName
    if (replyName == username) {
      println("*************")
      println("New reply: " + status.getText)
      val text = "@" + status.getUser.getScreenName + " " + doActionGetReply(status)
      println("Replying: " + text)
      val reply = new StatusUpdate(text).inReplyToStatusId(status.getId)
      twitter.updateStatus(reply)
    }
  }

  /**
   * A method that possibly takes an action based on a status
   * it has received, and then produces a response.
   */
  def doActionGetReply(status: Status) = {
    val text = status.getText.toLowerCase
    val followMatches = FollowRE.findAllIn(text)
    if (!followMatches.isEmpty) {
      val followSet = followMatches
	.next
	.drop(1)
	.split("\\s")
	.map {
	  case "me" => status.getUser.getScreenName
	  case screenName => screenName.drop(1)
	}
	.toSet
      //followSet.foreach(twitter.createFriendship)
      //"OK. I FOLLOWED " + followSet.map("@"+_).mkString(" ") + "."  
      "NO."
    } else {
      
      try {
	val StripLeadMentionRE(withoutMention) = text
	val statusList = 
	  SimpleTokenizer(withoutMention)
	    .filter(_.length > 3)
	    .filter(_.length < 10)
	    .filterNot(_.contains('/'))
	    .filter(tshrdlu.util.English.isSafe)
	    .sortBy(- _.length)
	    .toList
	    .take(4)
            .sliding(2)
            .map(_.mkString(" "))
	    .toList
	    .flatMap(w => twitter.search(new Query(w)).getTweets)
	extractText(statusList, withoutMention)
      }	catch { 
	case _: Throwable => "NO."
      }
    }
  
  }

  /**
   * Go through the list of Statuses, filter out the non-English ones and
   * any that contain (known) vulgar terms, strip mentions from the front,
   * filter any that have remaining mentions or links, and then return the
   * head of the set, if it exists.
   */
  def extractText(statusList: List[Status], tweet: String) = {
    val desiredSentiment = getSentiment(tweet)
    val mult = if(desiredSentiment == 0) -1 else 1
    val useableTweets = statusList
      .map(_.getText)
      .map {
	case StripMentionsRE(rest) => rest
	case x => x
      }
      .filterNot(_.contains('@'))
      .filterNot(_.contains('/'))
      .filter(tshrdlu.util.English.isEnglish)
      .filter(tshrdlu.util.English.isSafe)

    if (useableTweets.isEmpty) {
      "NO." 
    }
    else {
      val tweetWordCount = wordCountMap(tweet, 2)
      val responseWordCounts = for (response <- useableTweets.filter(resTweet => (resTweet.toLowerCase.trim != tweet.trim))) yield (response, wordCountMap(response, 2))
      val cosineSimResponses = for ((response, responseWordCount) <- responseWordCounts) yield (response, cosine(tweetWordCount, responseWordCount))
      //responseWordCounts.foreach(println)
      //cosineSimResponses.maxBy(_._2)._1
      cosineSimResponses.sortBy(-_._2).take(5).sortBy(x => mult * Math.abs(getSentiment(x._1) + mult * desiredSentiment)).head._1
    }
  }

  // Makes a word count vector for a string
  def wordCountMap(text: String, synonyms: Int = 0) = {
    (for (word <- SimpleTokenizer(text)) yield (Set(word.toLowerCase()) ++ synonymMap(word).take(synonyms).toSet)).flatten.filter(word => """[\p{P}]+""".r.findAllIn(word).isEmpty).groupBy(x=>x).mapValues(_.length).withDefaultValue(0)
  }

  // Computer the cosine between two vectors
  def cosine(x: Map[String, Int], y: Map[String, Int]) = {
    val dotProd = x.map { case (k,v) => v*y(k) }.sum
    dotProd/(norm(x)*norm(y))
  }

  // Compute the Euclidean norm of a vector
  def norm(x: Map[String, Int]) = Math.sqrt(x.values.map(Math.pow(_,2)).sum)

  def getSentiment(text: String) = {
    val words = SimpleTokenizer(text)
    val len = words.length.toDouble
    val percentPositive = words.count(English.positiveWords.contains) / len
    val percentNegative = words.count(English.negativeWords.contains) / len
    (percentPositive - percentNegative)
  }

}

