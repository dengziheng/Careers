package org.seacourt.mapreducejobs


import org.apache.hadoop.io.{Text, IntWritable}

import scala.collection.JavaConversions._

import org.dbpedia.extraction.wikiparser._

import scala.collection.immutable.TreeSet

import org.seacourt.utility._
import org.seacourt.mapreduce._

object WordInTopicCounter extends MapReduceJob[Text, Text, Text, IntWritable, Text, IntWritable]
{
    class JobMapper extends MapperType
    {
        override def map( topicTitle : Text, topicText : Text, output : MapperType#Context )
        {
            try
            {
                val parsed = Utils.wikiParse( topicTitle.toString, topicText.toString )
                
                val text = Utils.foldlWikiTree( parsed, List[String](), (element : Node, stringList : List[String] ) =>
                {
                    element match
                    {
                        case TextNode( text, line ) => text::stringList
                        case _ => stringList
                    }
                } )
                
                val words = Utils.luceneTextTokenizer( text.mkString( " " ).toLowerCase() )
                var seenSet = TreeSet[String]()
                for (word <- words )
                {   
                    if ( !seenSet.contains( word ) )
                    {
                        seenSet = seenSet + word
                        output.write( new Text(word), new IntWritable(1) )
                    }
                }
            }
            catch
            {
                case e : WikiParserException =>
                case _ => 
            }
        }
    }
    
    class JobReducer extends ReducerType
    {
        override def reduce( word : Text, values: java.lang.Iterable[IntWritable], output : ReducerType#Context )
        {
            var count = 0
            for ( value <- values )
            {
                count += 1
            }
            output.write( word, new IntWritable(count) )
        }
    }
    
    override def register( job : Job )
    {
        job.setMapperClass(classOf[JobMapper])
        job.setReducerClass(classOf[JobReducer])
    }
}

