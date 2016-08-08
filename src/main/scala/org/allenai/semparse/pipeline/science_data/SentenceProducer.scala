package org.allenai.semparse.pipeline.science_data

import org.json4s._

import com.mattg.pipeline.Step
import com.mattg.util.FileUtil
import com.mattg.util.JsonHelper

import scala.util.Random

/**
 * This is a grouping of Steps that produce sentences as output.  They can take varying input, but
 * they must produce as output a file that contains one sentence per line, possibly with an index.
 * Output format should be "[sentence]" or "[sentence index][tab][sentence]".
 *
 * This groups together things like SentenceSelector (which takes a corpus as input and finds good
 * sentences to use for training) and SentenceCorrupter (which takes sentences as input and
 * corrupts them to produce negative training data).  The purpose of having this as a superclass is
 * so that things like SentenceToLogic and BackgroundCorpusSearcher don't have to care about
 * whether they are dealing with the output of SentenceSelector or SentenceCorruptor.
 *
 * Note: I originally made this an abstract class, not a trait, so that I could easily access the
 * params and fileUtil from the class.  However, you can only inherit from one class, so
 * SentenceProdcuers couldn't also be SubprocessSteps, which was a problem.  So, at least one of
 * those two has to be a trait, not a class, and this one seemed to make more sense.  This is why
 * this trait has some funny extra defs in here (and why we need a complex return type on
 * SentenceProducer.create).  It is expected that this trait is used in conjunction with a Step,
 * which will already have these values defined.
 */
trait SentenceProducer {
  def params: JValue
  def fileUtil: FileUtil
  val baseParams = Seq("sentence producer type", "create sentence indices", "max sentences")
  val indexSentences = JsonHelper.extractWithDefault(params, "create sentence indices", false)
  val maxSentences = JsonHelper.extractAsOption[Int](params, "max sentences")

  def outputFile: String

  def outputSentences(sentences: Seq[String]) {
    val outputLines = sentences.zipWithIndex.map(sentenceWithIndex => {
      val (sentence, index) = sentenceWithIndex
      if (indexSentences) s"${index}\t${sentence}" else s"${sentence}"
    })
    val finalLines = maxSentences match {
      case None => outputLines
      case Some(max) => {
        val random = new Random
        random.shuffle(outputLines).take(max)
      }
    }
    fileUtil.writeLinesToFile(outputFile, finalLines)
  }
}

object SentenceProducer {
  def create(params: JValue, fileUtil: FileUtil): Step with SentenceProducer = {
    (params \ "sentence producer type") match {
      case JString("sentence selector") => new SentenceSelector(params, fileUtil)
      case JString("sentence corruptor") => new SentenceCorruptor(params, fileUtil)
      case JString("kb sentence corruptor") => new CorruptedSentenceSelector(params, fileUtil)
      case JString("question interpreter") => new QuestionInterpreter(params, fileUtil)
      case JString("manually provided") => new ManuallyProvidedSentences(params, fileUtil)
      case _ => throw new IllegalStateException("unrecognized SentenceProducer parameters")
    }
  }
}

/**
 * This SentenceProducer lets you manually override the pipeline, giving a sentence file that is
 * not generated by one of the steps here.  In general, this should be used sparingly, mostly for
 * testing or while things are still in development, as it kind of defeats the whole purpose of the
 * pipeline code.
 */
class ManuallyProvidedSentences(
  val params: JValue,
  val fileUtil: FileUtil
) extends Step(None, fileUtil) with SentenceProducer {
  implicit val formats = DefaultFormats
  override val name = "Manually Provided Sentences"

  val validParams = baseParams ++ Seq("filename")

  override val outputFile = (params \ "filename").extract[String]
  override val inputs: Set[(String, Option[Step])] = Set((outputFile, None))
  override val outputs = Set(outputFile)
  override val inProgressFile = outputFile.dropRight(4) + "_in_progress"

  override def _runStep() { }
}
