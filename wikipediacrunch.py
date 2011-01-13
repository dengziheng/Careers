import os
import re
import bz2
import math
import mwlib
import string
import sqlite3


# Stem words using nltk

from xml.etree.cElementTree import ElementTree, iterparse
from mwlib.refine import compat
from mwlib.parser import nodes


def createDb( conn ):
    cur = conn.cursor()
    cur.execute( 'CREATE TABLE topics( id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, wordCount INTEGER )' )
    cur.execute( 'CREATE TABLE words( id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, parentTopicCount INTEGER, inverseDocumentFrequency REAL )' )
    cur.execute( 'CREATE TABLE wordAssociation( topicId INTEGER, wordId INTEGER, termFrequency REAL, termWeight REAL )' )
    cur.execute( 'CREATE INDEX i1 ON words(name)' )
    cur.execute( 'CREATE INDEX i2 ON wordAssociation(wordId)' )
    cur.execute( 'CREATE INDEX i3 ON wordAssociation(wordId, topicId)' )
    conn.commit()
    
wordIds = {}
validWord = re.compile('[a-z][a-z0-9\+\-]+')

def getTopicsForWord( conn, word ):
    return conn.execute(
        "SELECT t2.title, t1.termWeight FROM wordAssociation AS t1 INNER JOIN topics AS t2 ON t1.topicId=t2.id "
        "INNER JOIN words AS t3 ON t1.wordId=t3.id WHERE t3.name='assistants' ORDER BY t1.termWeight DESC", [word] )

def mungeWords(words):
    filtered = []
    for w in words:
        w = w.strip('"\'.();:,?@#<>/')
        if w.startswith('{{') and w.endswith('}}'):
            pass
        elif validWord.match( w ) == None:
            pass
        else:
            filtered.append( w )
    return filtered

def parseTree(mwel):
    res = []
    if isinstance( mwel, nodes.Text ):
        res += [v.strip().lower() for v in mwel.asText().split(' ')]
    elif  isinstance( mwel, nodes.Article ) or isinstance( mwel, nodes.Section ) or isinstance( mwel, nodes.Item ) or isinstance( mwel, nodes.Style ) or isinstance( mwel, nodes.Node ):
        for v in mwel:
            res += parseTree(v)
    else:
        print 'Unexpected node type:', type(mwel)
        
    return res
    
def parsePage( text ):
    words = parseTree( compat.parse_txt(text, lang=None) )
    return mungeWords(words)
    
def addPage( conn, title, text ):
    if not title.startswith('Category:') and not title.startswith('Portal:') and not title.startswith('User:'):
        print 'Adding page: ', title
        #words = simpleParsePage( text )
        words = parsePage( text )

        cur = conn.cursor()
        cur.execute( 'INSERT INTO topics VALUES(NULL, ?, ?)', (title, len(words)) )
        topicId = cur.lastrowid
        topicWordCount = {}
        for word in words:
            if word not in wordIds:
                cur.execute( 'INSERT INTO words VALUES( NULL, ?, 0, 0 )', [word] )
                wordIds[word] = cur.lastrowid
            wordId = wordIds[word]
            if word not in topicWordCount:
                topicWordCount[wordId] = 0
            topicWordCount[wordId] += 1

        numWords = len(words)
        for wordId, count in topicWordCount.items():
            termFrequency = float(count) / float(numWords)
            cur.execute( 'UPDATE words SET parentTopicCount=parentTopicCount+1 WHERE id=?', [wordId] )
            cur.execute( 'INSERT INTO wordAssociation VALUES(?, ?, ?, 0)', [topicId, wordId, termFrequency] )
            
def buildWeights( conn ):
    print 'Building inverse document frequencies'
    count = conn.execute( 'SELECT COUNT(*) FROM topics' ).fetchone()[0]
    res = conn.execute( 'UPDATE words SET inverseDocumentFrequency=log(? / parentTopicCount)', [count] )
    conn.commit()

    print 'Building word weightings'
    res = conn.execute( 'UPDATE wordAssociation SET termWeight=(termFrequency * (SELECT inverseDocumentFrequency FROM words WHERE id=wordId))' )
    conn.commit()
    print 'Complete'

def run():
    fileName = 'data/Wikipedia-small-snapshot.xml.bz2'
    dbFileName = 'parsed.sqlite3'

    existsAlready = os.path.exists( dbFileName )
    conn = sqlite3.connect( dbFileName, detect_types=sqlite3.PARSE_DECLTYPES )
    if not existsAlready:
        createDb( conn )
    conn.create_function( 'log', 1, math.log )

    if 1:
        fileStream = bz2.BZ2File( fileName, 'r' )
        for event, element in iterparse( fileStream ):
            if element.tag == 'page':
                title = element.find('title').text
                
                textElement = element.find('revision').find('text')
                if textElement != None:
                    text = textElement.text
                    addPage( conn, title, text )
                element.clear()
        conn.commit()
    
    buildWeights( conn )
                
if __name__ == '__main__':
    run()
