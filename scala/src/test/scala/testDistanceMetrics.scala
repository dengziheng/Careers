import org.scalatest.FunSuite
import scala.collection.immutable.{HashMap}

import scala.xml._
import org.seacourt.utility._


class TopicVector( val id : Int )
{
    type TopicId = Int
    type TopicWeight = Double
    
    class TopicElement( val weight : TopicWeight, val name : String )
    {
    }
    
    private var topics = HashMap[TopicId, TopicElement]()
    
    def addTopic( id : TopicId, weight : TopicWeight, name : String )
    {
        topics = topics.updated( id, new TopicElement( weight, name ) )
    }
    
    def distance( other : TopicVector ) =
    {
        var AB = 0.0
        var AA = 0.0
        var BB = 0.0
        
        var weightedMatches = List[(Double, String)]()
        
        for ( (id, te) <- topics )
        {
            if ( other.topics.contains(id) )
            {
                val otherte = other.topics(id)
                
                val combinedWeight = te.weight * otherte.weight
                val priorityWeight = combinedWeight / math.sqrt( (te.weight*te.weight) + (otherte.weight*otherte.weight) )
                
                
                weightedMatches = (priorityWeight, te.name) :: weightedMatches
                
                AB += combinedWeight
            }
            AA += (te.weight*te.weight)
        }
        
        for ( (id, te) <- other.topics )
        {
            BB += (te.weight*te.weight)
        }
        
        val cosineDist = AB / (math.sqrt(AA) * math.sqrt(BB))
        
        ( cosineDist, weightedMatches.sortWith( _._1 > _._1 ).slice(0, 15) )
    }
}

class DistanceMetricTest extends FunSuite
{
    def makeTopicVector( fileName : String, documentId : Int ) =
    {
        val data = XML.loadFile( fileName )
        
        // Pull out the name map at the end of the resolution file
        val nameMap = (data \\ "topic").foldLeft( HashMap[Int, String]() )( (c, el) => c.updated( (el \\ "id").text.toInt, (el \\ "name").text ) )
        
        val topicWeightings = new AutoMap[Int, Double]( x => 0.0 )
        for ( site <- data \ "sites" \ "site" )
        {
            val id = (site \ "id").text.toInt
            val topicId = (site \ "topicId").text.toInt
            val weight = (site \ "weight").text.toDouble
            
            for ( peer <- site \\ "peer" )
            {
                val peerId = (peer \\ "id").text.toInt
                val weightings = (peer \\ "component").foldLeft( List[(Int, Double)]() )( (l, el) => ( (el \\ "contextTopicId").text.toInt, (el \\ "weight").text.toDouble) :: l )
                
                for ( (id, weight) <- weightings )
                {
                    topicWeightings.set( id, topicWeightings(id) + weight )
                }
            }
        }
        
        val topicVector = new TopicVector(documentId)
        for ( (id, weight) <- topicWeightings )
        {
            val name = nameMap(id)
            if ( !name.startsWith("Category:") )
            {
                topicVector.addTopic( id, weight, nameMap(id) )
            }                
        }
        
        topicVector
    }
    

    test( "DistanceMetricTest" )
    {
        var tvs = List[TopicVector]()
        for ( i <- 1 until 16 )
        {
            val tv = makeTopicVector( "/home/alexw/AW/optimal/scala/ambiguityresolution%d.xml".format(i), i )
            tvs = tv :: tvs
        }
        
        for ( tv1 <- tvs; tv2 <- tvs if ( tv1.id < tv2.id && tv1.id != 6 && tv2.id != 6 ) )
        {
            val (dist, why) = tv1.distance(tv2)
            
            if ( dist > 0.01 )
            {
                println( "%d to %d: %2.6f".format( tv1.id, tv2.id, dist ) )
                
                for ( (weight, name) <- why )
                {
                    println( "  %s %2.6f".format( name, weight ) )
                }
            }
        }
    }
}


