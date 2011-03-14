import org.scalatest.FunSuite

import java.io.{File, BufferedReader, FileReader}
import scala.collection.mutable.ArrayBuffer
import com.almworks.sqlite4java._

import org.apache.lucene.util.Version.LUCENE_30
import org.apache.lucene.analysis.Token
import org.apache.lucene.analysis.tokenattributes.TermAttribute
import org.apache.lucene.analysis.standard.StandardTokenizer

import scala.xml.XML

class DisambiguatorTest extends FunSuite
{

    class PhraseTracker( val db : SQLiteConnection, val startIndex : Int )
    {
        var hasRealWords = false
        var parentId = -1L
        
        val wordQuery = db.prepare( "SELECT id FROM words WHERE name=?" )
        val phraseQuery = db.prepare( "SELECT id FROM phraseTreeNodes WHERE parentId=? AND wordId=?" )
        val topicQuery = db.prepare( "SELECT t1.id FROM topics AS t1 INNER JOIN phraseTopics AS t2 ON t1.id=t2.topicId WHERE t2.phraseTreeNodeId=?" )
        
        def update( rawWord : String, currIndex : Int ) : (Boolean, Int, Int, List[Int]) =
        {
            val word = rawWord.toLowerCase()
            if ( !StopWords.stopWordSet.contains( word ) )
            {
                hasRealWords = true
            }
            
            var success = false
            var topics = List[Int]()

            wordQuery.bind(1, word)
            if ( wordQuery.step() )
            {
                val wordId = wordQuery.columnInt(0)
                
                phraseQuery.bind(1, parentId)
                phraseQuery.bind(2, wordId)
                if ( phraseQuery.step() )
                {
                
                    val currentId = phraseQuery.columnInt(0)
                    if ( hasRealWords )
                    {
                        topicQuery.bind(1, currentId)
                        while ( topicQuery.step() )
                        {
                            val topicId = topicQuery.columnInt(0)
                            topics = topicId :: topics
                        }
                        topicQuery.reset()
                    }
                    
                    parentId = currentId
                    success = true
                }
                phraseQuery.reset()
            }
            wordQuery.reset()
            
            val result = (success, startIndex, currIndex, topics)
            return result
        }
    }

    test("Monbiot disambiguator test")
    {
        val testFileName = "./src/test/scala/data/monbiotTest.txt"
        val testDbName = "disambig.sqlite3"
        val testOutName = "out.html"
        
        val tokenizer = new StandardTokenizer( LUCENE_30, new BufferedReader( new FileReader( testFileName ) ) )
        
        var run = true
        val wordList = new ArrayBuffer[String]
        while ( run )
        {
            wordList.append( tokenizer.getAttribute(classOf[TermAttribute]).term() )
            run = tokenizer.incrementToken()
        }
        tokenizer.close()
        
        val db = new SQLiteConnection( new File(testDbName) )
        db.open()
        
        var openQueryList = List[PhraseTracker]()
        var wordIndex = 0
        for ( word <- wordList )
        {
            openQueryList = new PhraseTracker(db, wordIndex) :: openQueryList
            
            var newList = List[PhraseTracker]()
            for ( query <- openQueryList )
            {
                val (notTerminal, startIndex, endIndex, topicIds) = query.update(word, wordIndex)
                if ( notTerminal )
                {
                    newList = query :: newList
                }
                
                if ( topicIds != Nil )
                {
                    println( ":: " + wordList.slice(startIndex,endIndex+1) )
                }
            }
            openQueryList = newList
            wordIndex += 1
        }
        
        val result =
            <html>
                <head>
                    <title>Simple title</title>
                </head>
                <body>
                    <h1>Simple title</h1>
                    { wordList.reverse.mkString( " " ) }
                </body>
            </html>
            
        XML.save( testOutName, result )
        
        //println( wordList.reverse.toString )
        
        /*val dbFileName = 
        val db = new SQLiteConnection( new File(dbFileName) )
        
        val topicQuery = db.prepare( "SELECT t2.id, t2.name, t3.categoryId, t4.name FROM surfaceForms AS t1 INNER JOIN topics AS t2 ON t1.topicId=t2.id INNER JOIN categoryMembership AS t3 ON t1.topicId=t3.topicId INNER JOIN categories AS t4 ON t3.categoryId=t4.id WHERE t1.name=? ORDER BY t2.id, t3.categoryId" )*/
    }
    
    class DisambiguationAlternative
    {
    }
    
    test( "Disambiguation alternative test 1" )
    {
        val test = List( List(1), List(1,2), List(1,2,3) )
        
        
    }
    
    test( "Phrase topic combination test" )
    {
        //                  0      1       2       3      4        5        6       7       8      9      10    11
        val phrase = List( "on", "the", "first", "day", "of", "christmas", "my", "true", "love", "sent", "to", "me" )
        val topics = List( List(1,2,3), List(1,2,3,4,5), List(3,4,5), List(5), List(6,7,8), List(7,8) )
        
        
    }
}


