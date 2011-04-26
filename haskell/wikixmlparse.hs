{-# LANGUAGE DeriveDataTypeable #-}

import System.Console.CmdArgs
import System.Environment
import System.Exit
import Data.Maybe
import Data.List
import Data.DList (DList)
import Debug.Trace
import qualified Data.DList as DList
import Control.Monad
import System.IO
import Data.Binary
import Data.Binary.Put
import Data.Binary.Get
import Control.Exception

import Data.ByteString.Char8 (ByteString)
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as LazyByteString
import qualified Codec.Compression.BZip as BZip

import Text.XML.Expat.Proc
import Text.XML.Expat.Tree
import Text.XML.Expat.Format

-- cabal build: cabal install --prefix=/home/alex/Devel/AW/optimal/haskell --user
-- cabal configure for profiling: cabal configure --enable-executable-profiling --ghc-option=-auto-all --ghc-option=-caf-all
-- To rebuild core libraries with profiling enabled:
--   cabal install --reinstall bzlib --enable-library-profiling

-- Run time stats: ./test +RTS --sstderr
-- Profiling: ./test +RTS -p

-- Hexpat for XML parsing, custom parsec (or attoparsec) parser for MediaWiki

--testFile = "../data/Wikipedia-small-snapshot.xml.bz2"
--testFile = "../data/enwikiquote-20110414-pages-articles.xml.bz2"


-- Serialize out to lots of binary files using Data.Binary via BZip.compress


validPage pageData = case pageData of
    (Just _, Just _) -> True
    (_, _) -> False

scanChildren :: [UNode ByteString] -> DList ByteString
scanChildren c = case c of
    h:t -> DList.append (getContent h) (scanChildren t)
    []  -> DList.fromList []

getContent :: UNode ByteString -> DList ByteString
getContent treeElement =
    case treeElement of
        (Element name attributes children)  -> scanChildren children
        (Text text)                         -> DList.fromList [text]

rawData t = ((getContent.fromJust.fst) t, (getContent.fromJust.snd) t)

extractText page = do
    revision <- findChild (BS.pack "revision") page
    text <- findChild (BS.pack "text") revision
    return text

pageDetails tree =
    let pageNodes = filterChildren relevantChildren tree in
    let getPageData page = (findChild (BS.pack "title") page, extractText page) in
    map rawData $ filter validPage $ map getPageData pageNodes
    where
        relevantChildren node = case node of
            (Element name attributes children) -> name == (BS.pack "page")
            (Text _) -> False

{-
foreachPage pages count = case pages of
    h:t     -> do
        (mapM_ BS.putStr h)
        when ((mod count 1000) == 0) $ hPutStrLn stderr ("Count: " ++ (show count))
        foreachPage t $ count+1
    []      -> return []

outputPages pagesText = do
    let flattenedPages = map DList.toList pagesText
    --mapM_ (mapM_ BS.putStr) flattenedPages
    foreachPage flattenedPages 0
-}

data LazySerializingList a = LazySerializingList [a] deriving (Eq)

chunkList list chunkSize =
    let soFar = take chunkSize list in
    let size = length soFar in
    if (size == 0) then
        [(0, [])]
    else
        (size, soFar) : chunkList (drop size list) chunkSize

incrementalPut list chunkSize =
    mapM_ (\x -> put (fst x) >> put (snd x)) $ chunkList list chunkSize

{-
-- The type signature below means that for a Binary serializable object 'a' ('Binary a =>'),
-- this instance will serialize a list of [a] (Binary [a])

instance Binary a => Binary [a] where
    put l  = put (length l) >> mapM_ put l
    get    = do n <- get :: Get Int
                replicateM n get
-}

getLazySerializingList :: Binary a => Get [a]
getLazySerializingList = do
    length <- get :: Get Int
    headChunk <- get
    tailChunk <- if (length==0) then do
        return []
        else do
            tailData <- getLazySerializingList
            return tailData
    return $ headChunk ++ tailChunk

instance (Binary a) => Binary (LazySerializingList a) where
    put (LazySerializingList l) = incrementalPut l 255
    get = do
        listData <- getLazySerializingList
        return $ LazySerializingList listData

data Options = Options {
    inputFile       :: String,
    outputBase      :: String,
    recordsPerFile  :: Int  
} deriving (Eq, Show, Data, Typeable)

defaultOptions = Options { inputFile = "", outputBase = "", recordsPerFile = 100000 }

saveRecords :: Binary a => Int -> String -> [a] -> Int -> IO ()
saveRecords splitSize fileBase l index = do
    let head = take splitSize l
    case head of
        []        -> do
            return ()
        _         -> do
            let chunkedList = LazySerializingList head
            let serializable = encode chunkedList
            let compressed = BZip.compress serializable
            let currentChunkName = fileBase ++ "." ++ (show index) ++ ".bz"
            print $ "Current chunk name: " ++ currentChunkName
            LazyByteString.writeFile currentChunkName compressed
            -- Comment the next line out to re-instate laziness!
            saveRecords splitSize fileBase (drop splitSize l) (index+1)

blah = LazySerializingList ["1", "2", "3", "4", "5", "6", "7", "8", "9", "1", "2", "3", "4", "5", "6", "7", "8", "9", "1", "2", "3", "4", "5", "6", "7", "8", "9"]


main = do
    -- Quick check of encode functionality
    let res = encode blah
    let res2 = decode res
    print (blah == res2)
    --assert (blah == res2)

    opts <- cmdArgs defaultOptions
    
    let testFile = inputFile opts
    let outBase = outputBase opts
    let rpf = recordsPerFile opts
    
    let lazyRead fileName = LazyByteString.readFile fileName
    let readCompressed fileName = fmap BZip.decompress $ lazyRead fileName
    let parseXml byteStream = parse defaultParseOptions byteStream :: (UNode ByteString, Maybe XMLParseError)

    rawContent <- readCompressed testFile
    let (tree, mErr) = parseXml rawContent
    let pages = pageDetails tree
    let flattenedPages = map (\x -> (DList.toList $ fst x, DList.toList $ snd x)) pages
    
    saveRecords rpf outBase flattenedPages 0

    putStrLn "Complete!"
    exitWith ExitSuccess

