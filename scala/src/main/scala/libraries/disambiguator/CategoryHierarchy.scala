package org.seacourt.disambiguator

import scala.collection.immutable.{TreeMap, HashSet, HashMap}
import scala.collection.mutable.{ArrayBuffer, Stack}
import scala.xml.XML

import math.{log, pow, abs}
import java.io.{File, DataInputStream, FileInputStream}

import org.seacourt.utility.{NPriorityQ}
import org.seacourt.sql.SqliteWrapper._
import org.seacourt.utility._


object CategoryHierarchy
{
    type Weight = Double
    val overbroadCategoryCount = 20
    
    // CREATE TABLE topicInboundLinks( topicId INTEGER, count INTEGER );
    // INSERT INTO topicInboundLinks SELECT contextTopicId, sum(1) FROM linkWeights2 GROUP BY contextTopicId;
    // CREATE INDEX topicInboundLinksIndex ON topicInboundLinks(topicId);
    class CategoryHierarchy( fileName : String, val topicDb : SQLiteWrapper )
    {
        val hierarchy = new EfficientArray[EfficientIntIntDouble](0)
        hierarchy.load( new DataInputStream( new FileInputStream( new File(fileName) ) ) )
        
        def inboundCounts =
        {
            val topicNameQuery = topicDb.prepare( "SELECT name FROM topics WHERE id=?", Col[String]::HNil )
            
            var pairCounts = TreeMap[Int, Int]()
            for ( pair <- hierarchy )
            {
                val fromTopic = pair.first
                val toTopic = pair.second
                val weight = pair.third
                
                topicNameQuery.bind(fromTopic)
                val fromName = _1(topicNameQuery.toList(0)).get
                
                if (fromName.startsWith("Category:") )
                {
                    val oldCount = pairCounts.getOrElse(toTopic, 1)
                    pairCounts = pairCounts.updated( toTopic, oldCount + 1 )
                }
            }
            
            pairCounts.toList.sortWith( _._2 > _._2 )
        }
            
        def debugDumpCounts()
        {
            val topicNameQuery = topicDb.prepare( "SELECT name FROM topics WHERE id=?", Col[String]::HNil )
            
            for ( (id, count) <- inboundCounts )
            {
                topicNameQuery.bind(id)
                val name = _1(topicNameQuery.toList(0)).get
                println( "   ::: " + name + ", " + count )
            }
        }
        
        //val tooFrequent = inboundCounts.filter( _._2 > overbroadCategoryCount ).foldLeft(HashSet[Int]())( _ + _._1 )
                
        def toTop( topicIds : Seq[Int] ) =
        {
            val q = Stack[Int]()
            for ( id <- topicIds ) q.push( id )
            var seen = HashSet[Int]()
            
            //println( "::::::::::::: " + categoryIds.length + " " + q.length )
            
            val bannedCategories = HashSet(
                6393409, // Category:Categories by association,
                366521, // Category:Articles
                8591947, // Category:Standards by organization
                8771811, // Category:Organizations by type
                5850429, // Category:People by occupation
                2750118, // Category:Writers by format
                4027554, // Category:Subfields by academic discipline
                6761185, // Category:Metaphysics
                4572804, // Category:Philosophy by field
                5302049, // Category:Personal life
                1655098, // Category:Works by medium
                5667343, // Category:Organizations
                6940720, // Category:Concepts
                6575338, // Category:Concepts in epistemology
                368106, // Category:Critical thinking
                4571289, // Category:Mind
                5297085, // Category:Concepts in metaphysics
                1279771, // Category:Academic disciplines
                550759,  // Category:Categories by topic
                6400291, // Category:Society
                6760142  // Category:Interdisciplinary fields
            )
            
            // parent category => (child category, weight)
            val edgeList = ArrayBuffer[(Int, Int, Double)]()
            while ( !q.isEmpty )
            {
                val first = q.pop()

                var it = Utils.lowerBound( new EfficientIntIntDouble( first, 0, 0.0 ), hierarchy, (x:EfficientIntIntDouble, y:EfficientIntIntDouble) => x.less(y) )               
                while ( it < hierarchy.size && hierarchy(it).first == first )
                {
                    val row = hierarchy(it)
                    val parentId = row.second
                    val weight = row.third
                    
                    if ( !bannedCategories.contains(parentId) )//&& !tooFrequent.contains(parentId) )
                    {
                        edgeList.append( (first, parentId, weight) )
                        
                        if ( !seen.contains( parentId ) )
                        {
                            q.push( parentId )
                            seen = seen + parentId
                        }
                    }
                    it += 1
                }
            }
            
            edgeList
        }
    }
    
    class Edge( val source : Node, val sink : Node, val weight : Weight )
    {
        assert( weight >= 0.0 )
        assert( source != sink )
        
        var flow = 0
        source.addEdge( this )
        sink.addEdge( this )
        
        def remove()
        {
            source.edges -= this
            sink.edges -= this
        }
    }
    
    class Node( val id : Int )
    {
        var edges = HashSet[Edge]()
        var distance = Double.MaxValue
        var prev : Edge = null
        var enqueued = false
        
        def addEdge( edge : Edge )
        {
            edges += edge
        }
    }
    
    class Graph[Data]()
    {
        var allNodes = HashSet[Node]()
                
        def addNode( node : Node ) =
        {
            allNodes += node
        }
        
        def dijkstraVisit( start : Node, visitFn : (Node, Weight) => Unit )
        {
            for ( n <- allNodes )
            {
                n.distance = Double.MaxValue
                n.prev = null
                n.enqueued = false
            }
            
            val q = new NPriorityQ[Node]
            start.distance = 0.0
            start.enqueued = true
            q.add( start.distance, start )
            
            while ( !q.isEmpty )
            {
                val (distance, node) = q.popFirst()
                node.enqueued = false
                
             
                visitFn( node, distance )
                for ( edge <- node.edges )
                {
                    val s = if ( edge.source == node ) edge.sink else edge.source
                    val edgeWeight = edge.weight
                    if ( s.enqueued || s.distance == Double.MaxValue )
                    {
                        if ( s.distance != Double.MaxValue )
                        {
                            q.remove( s.distance, s )
                        }
                       
                        val thisDistance = distance + edgeWeight
                        if ( thisDistance < s.distance )
                        {
                            s.distance = thisDistance
                            s.prev = edge
                        }
                        
                        q.add( s.distance, s )
                        s.enqueued = true
                    }
                }    
            }
        }
        
        def connectedWithout( edge : Edge ) = true
    }
    
    class Builder( topicIds : Seq[Int], edges : Seq[(Int, Int, Double)] )
    {
        val g = new Graph()
        var topics : List[Node] = null
        initialise( edges )
        
        def initialise( edges : Seq[(Int, Int, Double)] ) =
        {
            var idToNode = HashMap[Int, Node]()
            def getNode( id : Int ) =
            {
                if ( idToNode.contains(id) )
                {
                    idToNode(id)
                }
                else
                {
                    val n = new Node(id)
                    g.addNode( n )
                    idToNode = idToNode.updated(id, n)
                    n
                }
            }
            for ( (fromId, toId, weight) <- edges )
            {
                val from = getNode(fromId)
                val to = getNode(toId)
                val edge = new Edge( from, to, weight )
            }
            
            topics = topicIds.toList.map( getNode(_) )
        }
        
        def run()
        {
            // Reset all flows to zero
            for ( node <- g.allNodes; e <- node.edges )
            {
                e.flow = 0
            }
            
            // 1: Mark off all relevant edges by pushing a unit of flow down each. Relevant edges
            //    are all those on a shortest path from one topic to another.
            for ( topic <- topics )
            {
                g.dijkstraVisit( topic, (node, height) => {} )
                
                for ( innerTopic <- topics )
                {
                    var it = innerTopic
                    
                    while ( it != null )
                    {
                        it.prev.flow += 1
                        it = if ( it.prev.source == it ) it.prev.sink else it.prev.source
                    }
                }
            }
            
            // 2: Remove all edges with zero flow. And then all disconnected nodes
            {
                var liveNodes = HashSet[Node]()
                for ( node <- g.allNodes )
                {
                    node.edges = node.edges.filter( _.flow > 0 )
                    
                    for ( e <- node.edges )
                    {
                        liveNodes += e.source
                        liveNodes += e.sink
                    }
                }
                
                g.allNodes = g.allNodes.filter( x => liveNodes.contains(x) )
            }
            
            // 3: Run reverse-delete MST builder on reduced graph. Longest edge first...
            val edges = ( for ( node <- g.allNodes; edge <- node.edges ) yield edge ).toList.sortWith( (x, y) => x.weight > y.weight )
            for ( edge <- edges )
            {
                if ( g.connectedWithout( edge ) )
                {
                    // Remove edge
                    edge.remove()
                }
            }
            
            // Foreach edge (starting at longest) remove if after graph remains connected
        }
    }
    
    /*
    type TopicId = Int
    
    class HierarchyBuilder( val topicIds : Seq[TopicId], graphEdges : Seq[(TopicId, TopicId, Double)] )
    {
        type NodeType = Node[TopicId]
        val g = new Graph[TopicId]()
        var idToNodeMap = HashMap[TopicId, NodeType]()
        
        init( graphEdges )
        
        private def init( graphEdges : Seq[(TopicId, TopicId, Double)] )
        {
            for ( (fromId, toId, weight) <- graphEdges )
            {
                val fromNode = getNode( fromId )
                val toNode = getNode( toId )
                
                fromNode.addSink( toNode, weight )
            }
        }
        
        private def getNode( topicId : TopicId ) =
        {
            if ( idToNodeMap.contains(topicId) )
            {
                idToNodeMap(topicId)
            }
            else
            {
                val newNode = g.newNode( topicId )
                idToNodeMap = idToNodeMap.updated( topicId, newNode )
                newNode
            }
        }
        
        def mergePass( liveTopics : HashSet[NodeType], getName: Int => String ) =
        {
            var merges = ArrayBuffer[(NodeType, List[NodeType])]()
            
            class MergeChoice( val theNode : NodeType, val height : Double, val mergeSize : Int )
            {
                def weight =
                {
                    val optimumSize = 6.0
                    val sizeDesirability = 1.0 / (pow(abs(optimumSize - mergeSize), 2.0) max 1.0)
                    
                    sizeDesirability / pow(height, 1.0)
                    //mergeSize / pow(height, 0.8)
                }
                
                def comp( other : MergeChoice ) =
                {
                    weight > other.weight
                }
            }
            
            var options = ArrayBuffer[MergeChoice]()
            g.dijkstraVisit( liveTopics.toSeq, (node, height) =>
            {
                // All incomings must be of lower height than the merge point
                val members = node.topicMembership
                val numMembers = members.size
                //val aveHeight = members.foldLeft(0.0)( _ + _._2 ) / numMembers.toDouble
                val maxHeight = members.foldLeft(0.0)( _ max _._2 )
                
                
                if ( numMembers > 1 )
                {
                    options.append( new MergeChoice( node, maxHeight, numMembers ) )
                }
            } )
            
            g.dumpLabellings( x => (x.data + "/" + getName( x.data )) )
            
            if ( options.size > 0 )
            {
                val mergeOptionsByWeight = options.toList.sortWith( _.weight > _.weight )
                
                var mergedAlready = HashSet[TopicId]()
                val beforeSize = merges.size
                
                for ( bestChoice <- mergeOptionsByWeight )
                {
                    val mergeNode = bestChoice.theNode
                    val mergingNodes = mergeNode.topicMembership.toList.map( _._1 )
                    
                    if ( mergingNodes.foldLeft( true )( (x, y) => x && !mergedAlready.contains(y.data) ) )
                    {
                        println( mergeNode.data + ", " + mergingNodes.map(_.data) + ": " + bestChoice.height + ", " + bestChoice.mergeSize + ", " + bestChoice.weight )                            
                        merges.append( (mergeNode, mergingNodes.toList) )
                        
                        for ( mn <- mergingNodes ) mergedAlready += mn.data
                    }
                }
                
                for ( (mergeNode, mergingNodes) <- merges.slice( beforeSize, merges.size ) )
                {
                    println( "Delabelling: " + mergingNodes.map(_.data) )
                    // Remove all labelling for the merged nodes
                    g.dijkstraVisit( liveTopics.toSeq, (node, height) =>
                    {
                        for ( n <- mergingNodes ) if ( node.topicMembership.contains(n) ) node.topicMembership -= n
                    } )
                    
                    for ( removed <- mergingNodes; n <-g.allNodes ) assert( !n.topicMembership.contains(removed) )
                }
            }
            
            merges
        }
        
        def updateTopicMembership( node : NodeType, topicNode : NodeType, height : Double )
        {
            // You can't really be a member of a topic of your route in is hugely longer than
            // the min route to the topic
            if ( height < node.minHeight * 1.2 )
            {
                node.topicMembership = node.topicMembership.updated( topicNode, height )
            }
        }
        
        def run( getName: Int => String ) =
        {
            var merges = ArrayBuffer[(NodeType, List[NodeType])]()
            var liveTopics = topicIds.foldLeft(HashSet[NodeType]())( _ + getNode(_) )
            
            println( "Set up the min distance field by doing an all-nodes visit." )
            g.dijkstraVisit( liveTopics.toSeq, (node, height) =>
            {
                println( "---> " + node.data + ", " + getName(node.data) + ": " + node.minHeight + ", " + height )
            } )
            
         
            // Label all nodes with their topic incidence and height
            println( "Label all nodes with their topic incidence and height" )
            for ( topicNode <- liveTopics )
            {
                println( "  labelling: " + topicNode.data )
                g.dijkstraVisit( topicNode :: Nil, (node, height) => updateTopicMembership( node, topicNode, height ) )
            }
            
            // Find the best merge point according to min topic height, num topics, topic importance (incident edge count?)
            var finished = false
            while ( liveTopics.size > 1 && !finished )
            {
                println( "Find the best merge points: " + liveTopics.size )
                
                // Run the merges
                var passComplete = false
                
                val thisPassMerges = ArrayBuffer[(NodeType, List[NodeType])]()
                
                // It would seem better not to force merges after doing a strip,
                // in case things end up over-generalising too early
                //while ( !passComplete )
                {
                    val thisRunMerges = mergePass( liveTopics, getName )
                    
                    if ( !thisRunMerges.isEmpty )
                    {
                        thisPassMerges.appendAll( thisRunMerges )
                        
                        for ( (into, froms) <- thisRunMerges; from <- froms ) liveTopics -= from
                    }
                    else
                    {
                        passComplete = true
                    }
                }
                
                if ( thisPassMerges.isEmpty )
                {
                    finished = true
                }
                else
                {
                    for ( (into, froms) <- thisPassMerges )
                    {
                        liveTopics += into
                        
                        // Label the graph up with the new merged node
                        g.dijkstraVisit( into :: Nil, (node, height) => updateTopicMembership( node, into, height ) )
                    }
                    merges.appendAll( thisPassMerges )
                }
            }
            
            var elementMap = HashMap[NodeType, scala.xml.Elem]()
            var hasParent = HashSet[NodeType]()
            for ( (mergeInto, froms) <- merges )
            {
                val el =
                    <element name={getName(mergeInto.data)} id={mergeInto.data.toString}>
                    {
                        for ( from <- froms ) yield
                        {
                            val element =
                                if ( elementMap.contains( from ) ) elementMap(from)
                                else <element name={getName(from.data)} id={from.data.toString}/>
                                
                            hasParent += from
                            
                            element
                        }
                    }
                    </element>
                
                elementMap = elementMap.updated(mergeInto, el)
            }

            val output =
                <structure>
                {
                    for ( (node, elem) <- elementMap.filter( x => !hasParent.contains(x._1) ) ) yield elem
                }
                </structure>
                
            XML.save( "structure.xml", output, "utf8" )
            merges.map( x => ( x._1.data, x._2.map( _.data ) ) )
        }
    }*/
}
