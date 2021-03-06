package org.seacourt.disambiguator

import scala.collection.immutable.{HashMap}

import org.seacourt.utility._

class TopicElement( val weight : Double, val name : String, val groupId : Int, val primaryTopic : Boolean )
{
}

class TopicVector()
{
    type TopicId = Int
    type TopicWeight = Double
    
    var topics = HashMap[TopicId, TopicElement]()
    
    def size = topics.size
    
    def addTopic( id : TopicId, weight : TopicWeight, name : String, groupId : Int, primaryTopic : Boolean )
    {
        topics = topics.updated( id, new TopicElement( weight, name, groupId, primaryTopic ) )
    }
    
    def addTopic( id : TopicId, te : TopicElement ) =
    {
        topics = topics.updated( id, te )
        this
    }
    
     def prunedToTop( N : Int ) =
     {
         val prunedSortedList = topics.toList.sortWith( _._2.weight > _._2.weight ).slice( 0, N )
         
         prunedSortedList.foldLeft( new TopicVector() )( (tv, idte) => tv.addTopic( idte._1, idte._2 ) )
     }
    
     def pruneSolitaryContexts( other : TopicVector, strict : Boolean )
     {
         topics = topics.filter( el =>
         {
             val (id, te) = el
             te.primaryTopic || (!strict && (other.topics.contains(id) && other.topics(id).primaryTopic))
         } )
     }    
    
    def distance( other : TopicVector ) =
    {
        var AB = 0.0
        var AA = 0.0
        var BB = 0.0
        
        // Weight, name, groupId
        var weightedMatches = List[(Double, Int, TopicElement)]()
        
        // Choose top N from each
        val topTopics = topics
        val topOtherTopics = other.topics
        for ( (id, te) <- topTopics )
        {
            if ( topOtherTopics.contains(id) )
            {
                val otherte = topOtherTopics(id)
                
                val combinedWeight = te.weight * otherte.weight
                val priorityWeight = combinedWeight / math.sqrt( (te.weight*te.weight) + (otherte.weight*otherte.weight) )
                
                
                weightedMatches = (priorityWeight, id, te) :: weightedMatches
                
                AB += combinedWeight
            }
            AA += (te.weight*te.weight)
        }
        
        for ( (id, te) <- other.topics )
        {
            BB += (te.weight*te.weight)
        }
        
        val cosineDist = AB / (math.sqrt(AA) * math.sqrt(BB))
        
        ( cosineDist, weightedMatches.sortWith( _._1 > _._1 ) )
    }
    
    def rankedAndGrouped =
    {
        val rankedTopics = topics.map( _._2 ).filter( _.primaryTopic ).toList.sortWith( _.weight > _.weight ).zipWithIndex
        var grouped = new AutoMap[Int, List[(Int, TopicElement)]]( x => Nil )
        for ( (te, rank) <- rankedTopics )
        {
            grouped.set( te.groupId, (rank, te) :: grouped(te.groupId) )
        }
        
        grouped.map( el =>
        {
            val (gid, tes) = el
            
            var sum = 0.0
            var count = 0
            for ( (rank, te) <- tes )
            {
                sum += rank
                count += 1
            }

            (sum/count.toDouble, tes )
        } ).toList.sortWith( _._1 < _._1 ).map( _._2 )
    }
}

class DocumentDigest( val id : Int, val topicVector : TopicVector, val topicLinks : List[(Int, Int, Double)] )
{
    type LinksType = List[(Int, Int, Double)]
}


