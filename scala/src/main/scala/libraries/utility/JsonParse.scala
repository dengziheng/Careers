package org.seacourt.utility

/*
* Copyright 2009-2010 WorldWide Conferencing, LLC
* Copyright 2010 Vladimir Kirichenko <vladimir.kirichenko@gmail.com>
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package butter4s.json

object Parser {
	import java.io._

	class ParseException( message: String, cause: Exception ) extends Exception( message, cause ) {
		def this( message: String ) = this ( message, null )
	}

	/**
	 * Parsed tokens from low level pull parser.
	 */
	sealed abstract class Token
	case object OpenObj extends Token
	case object CloseObj extends Token
	case class FieldStart( name: String ) extends Token
	case object End extends Token
	case class StringVal( value: String ) extends Token
	case class IntVal( value: BigInt ) extends Token
	case class DoubleVal( value: Double ) extends Token
	case class BoolVal( value: Boolean ) extends Token
	case object NullVal extends Token
	case object OpenArr extends Token
	case object CloseArr extends Token

	/**
	 * Return parsed JSON.
	 * @throws ParseException is thrown if parsing fails
	 */
	def parse( s: String ): Any = parse( new Buffer( new StringReader( s ), false ) )

	/**
	 * Return parsed JSON.
	 * @param closeAutomatically true (default) if the Reader is automatically closed on EOF
	 * @throws ParseException is thrown if parsing fails
	 */
	def parse( s: Reader, closeAutomatically: Boolean = true ): Any =
		parse( new Buffer( s, closeAutomatically ) )

	/**
	 * Return parsed JSON.
	 */
	def parseOpt( s: String ): Option[Any] =
		try {Some( parse( s ) )} catch {case e: Exception => None}

	/**
	 * Return parsed JSON.
	 * @param closeAutomatically true (default) if the Reader is automatically closed on EOF
	 */
	def parseOpt( s: Reader, closeAutomatically: Boolean = true ): Option[Any] =
		try {Some( parse( s, closeAutomatically ) )} catch {case e: Exception => None}

	/**
	 * Parse in pull parsing style.
	 * Use <code>p.nextToken</code> to parse tokens one by one from a string.
	 */
	def parse[A]( s: String, p: Lexer => A ): A = parse( new StringReader( s ), p )

	/**
	 * Parse in pull parsing style.
	 * Use <code>p.nextToken</code> to parse tokens one by one from a stream.
	 * The Reader must be closed when parsing is stopped.
	 */
	def parse[A]( s: Reader, p: Lexer => A ): A = p( new Lexer( new Buffer( s, false ) ) )

	private def parse( buf: Buffer ): Any = {
		try {
			parse( new Lexer( buf ) )
		} catch {
			case e: ParseException => throw e
			case e: Exception => throw new ParseException( "parsing failed", e )
		} finally {buf.release}
	}

	private[json] def unquote( string: String ): String =
		unquote( new Parser.Buffer( new java.io.StringReader( string ), false ) )

	private[json] def unquote( buf: Parser.Buffer ): String = {
		def unquote0( buf: Parser.Buffer, base: String ): String = {
			val s = new java.lang.StringBuilder( base )
			var c = '\\'
			while ( c != '"' ) {
				if ( c == '\\' ) {
					buf.next match {
						case '"' => s.append( '"' )
						case '\\' => s.append( '\\' )
						case '/' => s.append( '/' )
						case 'b' => s.append( '\b' )
						case 'f' => s.append( '\f' )
						case 'n' => s.append( '\n' )
						case 'r' => s.append( '\r' )
						case 't' => s.append( '\t' )
						case 'u' =>
							val chars = Array( buf.next, buf.next, buf.next, buf.next )
							val codePoint = Integer.parseInt( new String( chars ), 16 )
							s.appendCodePoint( codePoint )
						case _ => s.append( '\\' )
					}
				} else s.append( c )
				c = buf.next
			}
			s.toString
		}

		buf.mark
		var c = buf.next
		while ( c != '"' ) {
			if ( c == '\\' ) {
				return unquote0( buf, buf.substring )
			}
			c = buf.next
		}
		buf.substring
	}

	private def parse( p: Lexer ) = {
		val st = new ParserStack
		var token: Token = null
		do {
			token = p.nextToken
			token match {
				case BoolVal( x ) => st.pushValue( x )
				case NullVal => st.pushValue( null )
				case IntVal( x ) => st.pushValue( x.toDouble )
				case DoubleVal( x ) => st.pushValue( x )
				case StringVal( x ) => st.pushValue( x )
				case OpenArr => st.openArray
				case CloseArr => st.closeArray
				case OpenObj => st.openObject
				case CloseObj => st.closeObject
				case FieldStart( name ) => st.objectField( name )
				case End =>
			}
		} while ( token != End )
		st.result
	}

	private class ParserStack {
		private val stack = new java.util.LinkedList[Any]()

		def pushValue( v: Any ) = stack.peek match {
			case si: List[_] => stack.set( 0, v :: si )
			case FieldName( n ) => stack.remove; stack.addFirst( stack.remove.asInstanceOf[Map[String, Any]] + ( n -> v ) )
			case _ => stack.addFirst( v )
		}

		def openObject = stack.addFirst( Map[String, Any]() )

		private case class FieldName( name: String )

		def objectField( name: String ) = pushValue( FieldName( name ) )

		def closeObject = try pushValue( stack.remove.asInstanceOf[Map[String, Any]] ) catch {case e: ClassCastException => fail( e )}

		def openArray = stack.addFirst( Nil )

		def closeArray = try pushValue( stack.remove.asInstanceOf[List[Any]].reverse ) catch {case e: ClassCastException => fail( e )}

		def result = if ( stack.size != 1 ) fail( null ) else stack.getFirst

		def fail( e: Exception ) = throw new ParseException( "cannot parse. \nStack:\n" + stack, e )
	}

	private val EOF = ( -1 ).asInstanceOf[Char]

	class Lexer( buf: Buffer ) {
		import java.util.LinkedList

		private[this] val blocks = new LinkedList[BlockMode]()
		private[this] var fieldNameMode = true

		def fail( msg: String ) = throw new ParseException( msg + "\nNear: " + buf.near, null )

		/**Parse next Token from stream.
		 */
		def nextToken: Token = {
			def isDelimiter( c: Char ) = c == ' ' || c == '\n' || c == ',' || c == '\r' || c == '\t' || c == '}' || c == ']'

			def parseFieldName: String = {
				buf.mark
				var c = buf.next
				while ( c != EOF ) {
					if ( c == '"' ) return buf.substring
					c = buf.next
				}
				fail( "expected string end" )
			}

			def parseString: String = {
				try {
					unquote( buf )
				} catch {
					case _ => fail( "unexpected string end" )
				}
			}

			def parseValue( first: Char ) = {
				var wasInt = true
				var doubleVal = false
				val s = new StringBuilder
				s.append( first )
				while ( wasInt ) {
					val c = buf.next
					if ( c == '.' || c == 'e' || c == 'E' ) {
						doubleVal = true
						s.append( c )
					} else if ( !( Character.isDigit( c ) || c == '.' || c == 'e' || c == 'E' || c == '-' ) ) {
						wasInt = false
						buf.back
					} else s.append( c )
				}
				val value = s.toString
				if ( doubleVal ) DoubleVal( value.toDouble ) else IntVal( BigInt( value ) )
			}

			while ( true ) {
				buf.next match {
					case c if EOF == c =>
						buf.automaticClose
						return End
					case '{' =>
						blocks.addFirst( OBJECT )
						fieldNameMode = true
						return OpenObj
					case '}' =>
						blocks.poll
						return CloseObj
					case '"' =>
						if ( fieldNameMode && blocks.peek == OBJECT ) return FieldStart( parseFieldName )
						else {
							fieldNameMode = true
							return StringVal( parseString )
						}
					case 't' =>
						fieldNameMode = true
						if ( buf.next == 'r' && buf.next == 'u' && buf.next == 'e' ) {
							return BoolVal( true )
						}
						fail( "expected boolean" )
					case 'f' =>
						fieldNameMode = true
						if ( buf.next == 'a' && buf.next == 'l' && buf.next == 's' && buf.next == 'e' ) {
							return BoolVal( false )
						}
						fail( "expected boolean" )
					case 'n' =>
						fieldNameMode = true
						if ( buf.next == 'u' && buf.next == 'l' && buf.next == 'l' ) {
							return NullVal
						}
						fail( "expected null" )
					case ':' =>
						fieldNameMode = false
					case '[' =>
						blocks.addFirst( ARRAY )
						return OpenArr
					case ']' =>
						fieldNameMode = true
						blocks.poll
						return CloseArr
					case c if Character.isDigit( c ) || c == '-' =>
						fieldNameMode = true
						return parseValue( c )
					case c if isDelimiter( c ) =>
					case c => fail( "unknown token " + c )
				}
			}
			buf.automaticClose
			End
		}

		sealed abstract class BlockMode
		case object ARRAY extends BlockMode
		case object OBJECT extends BlockMode
	}

	/* Buffer used to parse JSON.
	 * Buffer is divided to one or more segments (preallocated in Segments pool).
	 */
	private[json] class Buffer( in: Reader, closeAutomatically: Boolean ) {
		var offset = 0
		var curMark = -1
		var curMarkSegment = -1
		private[this] var segments: List[Segment] = List( Segments.apply() )
		private[this] var segment: Array[Char] = segments.head.seg
		private[this] var cur = 0 // Pointer which points current parsing location
		private[this] var curSegmentIdx = 0 // Pointer which points current segment

		def mark = {curMark = cur; curMarkSegment = curSegmentIdx}

		def back = cur = cur - 1

		def next: Char = {
			if ( cur == offset && read < 0 ) EOF
			else {
				val c = segment( cur )
				cur += 1
				c
			}
		}

		def substring = {
			if ( curSegmentIdx == curMarkSegment ) new String( segment, curMark, cur - curMark - 1 )
			else { // slower path for case when string is in two or more segments
				var parts: List[(Int, Int, Array[Char])] = Nil
				var i = curSegmentIdx
				while ( i >= curMarkSegment ) {
					val s = segments( i ).seg
					val start = if ( i == curMarkSegment ) curMark else 0
					val end = if ( i == curSegmentIdx ) cur else s.length + 1
					parts = (start, end, s) :: parts
					i = i - 1
				}
				val len = parts.map( p => p._2 - p._1 - 1 ).foldLeft( 0 )( _ + _ )
				val chars = new Array[Char]( len )
				i = 0
				var pos = 0

				while ( i < parts.size ) {
					val (start, end, b) = parts( i )
					val partLen = end - start - 1
					System.arraycopy( b, start, chars, pos, partLen )
					pos = pos + partLen
					i = i + 1
				}
				new String( chars )
			}
		}

		def near = new String( segment, ( cur - 20 ) max 0, ( cur + 20 ) min offset )

		def release = segments.foreach( Segments.release )

		private[Parser] def automaticClose = if ( closeAutomatically ) in.close

		private[this] def read = {
			if ( offset >= segment.length ) {
				val newSegment = Segments.apply()
				offset = 0
				segment = newSegment.seg
				segments = segments ::: List( newSegment )
				curSegmentIdx = segments.length - 1
			}

			val length = in.read( segment, offset, segment.length - offset )
			cur = offset
			offset += length
			length
		}
	}

	/* A pool of preallocated char arrays.
	 */
	private[json] object Segments {
		import java.util.concurrent.ArrayBlockingQueue
		import java.util.concurrent.atomic.AtomicInteger

		private[json] val segmentSize = 1000
		private val maxNumOfSegments = 10000
		private val segmentCount = new AtomicInteger( 0 )
		private val segments = new ArrayBlockingQueue[Segment]( maxNumOfSegments )

		private[json] def clear = segments.clear

		def apply(): Segment = {
			val s = acquire
			// Give back a disposable segment if pool is exhausted.
			if ( s != null ) s else DisposableSegment( new Array( segmentSize ) )
		}

		private[this] def acquire: Segment = {
			val curCount = segmentCount.get
			val createNew =
			if ( segments.size == 0 && curCount < maxNumOfSegments )
				segmentCount.compareAndSet( curCount, curCount + 1 )
			else false

			if ( createNew ) RecycledSegment( new Array( segmentSize ) ) else segments.poll
		}

		def release( s: Segment ) = s match {
			case _: RecycledSegment => segments.offer( s )
			case _ =>
		}
	}

	sealed trait Segment {
		val seg: Array[Char]
	}
	case class RecycledSegment( seg: Array[Char] ) extends Segment
	case class DisposableSegment( seg: Array[Char] ) extends Segment
}
