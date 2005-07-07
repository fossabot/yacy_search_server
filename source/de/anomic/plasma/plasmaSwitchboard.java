// plasmaSwitchboard.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004, 2005
// last major change: 24.03.2005
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

/*
   This class holds the run-time environment of the plasma
   Search Engine. It's data forms a blackboard which can be used
   to organize running jobs around the indexing algorithm.
   The blackboard consist of the following entities:
   - storage: one plasmaStore object with the url-based database
   - configuration: initialized by properties once, then by external functions
   - job queues: for parsing, condensing, indexing
   - black/blue/whitelists: controls input and output to the index

   this class is also the core of the http crawling.
   There are some items that need to be respected when crawling the web:
   1) respect robots.txt
   2) do not access one domain too frequently, wait between accesses
   3) remember crawled URL's and do not access again too early
   4) priorization of specific links should be possible (hot-lists)
   5) attributes for crawling (depth, filters, hot/black-lists, priority)
   6) different crawling jobs with different attributes ('Orders') simultanoulsy

   We implement some specific tasks and use different database to archieve these goals:
   - a database 'crawlerDisallow.db' contains all url's that shall not be crawled
   - a database 'crawlerDomain.db' holds all domains and access times, where we loaded the disallow tables
     this table contains the following entities:
     <flag: robotes exist/not exist, last access of robots.txt, last access of domain (for access scheduling)>
   - four databases for scheduled access: crawlerScheduledHotText.db, crawlerScheduledColdText.db,
     crawlerScheduledHotMedia.db and crawlerScheduledColdMedia.db
   - two stacks for new URLS: newText.stack and newMedia.stack
   - two databases for URL double-check: knownText.db and knownMedia.db
   - one database with crawling orders: crawlerOrders.db

   The Information flow of a single URL that is crawled is as follows:
   - a html file is loaded from a specific URL within the module httpdProxyServlet as
     a process of the proxy.
   - the file is passed to httpdProxyCache. Here it's processing is delayed until the proxy is idle.
   - The cache entry is passed on to the plasmaSwitchboard. There the URL is stored into plasmaLURL where
     the URL is stored under a specific hash. The URL's from the content are stripped off, stored in plasmaLURL
     with a 'wrong' date (the date of the URL's are not known at this time, only after fetching) and stacked with
     plasmaCrawlerTextStack. The content is read and splitted into rated words in plasmaCondenser.
     The splitted words are then integrated into the index with plasmaSearch.
   - In plasmaSearch the words are indexed by reversing the relation between URL and words: one URL points
     to many words, the words within the document at the URL. After reversing, one word points
     to many URL's, all the URL's where the word occurrs. One single word->URL-hash relation is stored in
     plasmaIndexEntry. A set of plasmaIndexEntries is a reverse word index.
     This reverse word index is stored temporarly in plasmaIndexCache.
   - In plasmaIndexCache the single plasmaIndexEntry'ies are collected and stored into a plasmaIndex - entry
     These plasmaIndex - Objects are the true reverse words indexes.
   - in plasmaIndex the plasmaIndexEntry - objects are stored in a kelondroTree; an indexed file in the file system.

   The information flow of a search request is as follows:
   - in httpdFileServlet the user enters a search query, which is passed to plasmaSwitchboard
   - in plasmaSwitchboard, the query is passed to plasmaSearch.
   - in plasmaSearch, the plasmaSearch.result object is generated by simultanous enumeration of
     URL hases in the reverse word indexes plasmaIndex
   - (future: the plasmaSearch.result - object is used to identify more key words for a new search)

    

*/

package de.anomic.plasma;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;

import de.anomic.data.messageBoard;
import de.anomic.data.wikiBoard;
import de.anomic.htmlFilter.htmlFilterContentScraper;
import de.anomic.http.httpHeader;
import de.anomic.kelondro.kelondroException;
import de.anomic.kelondro.kelondroMSetTools;
import de.anomic.kelondro.kelondroTables;
import de.anomic.server.serverAbstractSwitch;
import de.anomic.server.serverCodings;
import de.anomic.server.serverCore;
import de.anomic.server.serverInstantThread;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSemaphore;
import de.anomic.server.serverSwitch;
import de.anomic.server.logging.serverLog;
import de.anomic.tools.bitfield;
import de.anomic.tools.crypt;
import de.anomic.yacy.yacyClient;
import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacySearch;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacySeedDB;

public final class plasmaSwitchboard extends serverAbstractSwitch implements serverSwitch {


    // load slots
    public static int crawlSlots = 10;
    public static int indexingSlots = 100;

    // couloured list management
    public static TreeSet blueList = null;
    public static TreeSet stopwords = null;
    
    // storage management
    private File                   cachePath;
    private File                   plasmaPath;
    public  File                   listsPath;
    public  plasmaURLPool          urlPool;
    public  plasmaWordIndex        wordIndex;
    public  plasmaSearch           searchManager;
    public  plasmaHTCache          cacheManager;
    public  plasmaSnippetCache     snippetCache;
    public  plasmaCrawlLoader      cacheLoader;
    public  plasmaSwitchboardQueue sbQueue;
    public  messageBoard           messageDB;
    public  wikiBoard              wikiDB;
    public  String                 remoteProxyHost;
    public  int                    remoteProxyPort;
    public  boolean                remoteProxyUse;
    public  plasmaCrawlProfile     profiles;
    public  plasmaCrawlProfile.entry defaultProxyProfile;
    public  plasmaCrawlProfile.entry defaultRemoteProfile;
    public  distributeIndex        indexDistribution;
    public  HashMap                outgoingCookies, incomingCookies;
    public  kelondroTables         facilityDB;
    public  plasmaParser           parser;
    public  plasmaWordIndexClassicCacheMigration classicCache;
    
    private serverSemaphore shutdownSync = new serverSemaphore(0);
    private boolean terminate = false;
    
    private Object  crawlingPausedSync = new Object();
    private boolean crawlingIsPaused = false;  
    
    public plasmaSwitchboard(String rootPath, String initPath, String configPath) throws IOException {
	super(rootPath, initPath, configPath);
        
        // set loglevel and log
	setLog(new serverLog("PLASMA"));
        
	// load values from configs
	plasmaPath   = new File(rootPath, getConfig("dbPath", "PLASMADB"));
        listsPath      = new File(rootPath, getConfig("listsPath", "LISTS"));
        remoteProxyHost = getConfig("remoteProxyHost", "");
        try {
            remoteProxyPort = Integer.parseInt(getConfig("remoteProxyPort", "3128"));
        } catch (NumberFormatException e) {
            remoteProxyPort = 3128;
        }
        if (getConfig("remoteProxyUse", "false").equals("true")) {
            remoteProxyUse = true;
        } else {
            remoteProxyUse = false;
            remoteProxyHost = null;
            remoteProxyPort = 0;
        }
        
        
        if (!(listsPath.exists())) listsPath.mkdirs();
        
	// load coloured lists
	if (blueList == null) {
	    // read only once upon first instantiation of this class
	    String f = getConfig("plasmaBlueList", null);
	    if (f != null) blueList = loadList(new File(f)); else blueList= new TreeSet();
	}

        // load stopwords
        if (stopwords == null) {
            stopwords = loadList(new File(rootPath, "yacy.stopwords"));
        }
        
	// read memory amount
        int ramLURL    = Integer.parseInt(getConfig("ramCacheLURL", "1024")) / 1024;
        int ramNURL    = Integer.parseInt(getConfig("ramCacheNURL", "1024")) / 1024;
        int ramEURL    = Integer.parseInt(getConfig("ramCacheEURL", "1024")) / 1024;
        int ramRWI     = Integer.parseInt(getConfig("ramCacheRWI",  "1024")) / 1024;
        int ramHTTP    = Integer.parseInt(getConfig("ramCacheHTTP", "1024")) / 1024;
        int ramMessage = Integer.parseInt(getConfig("ramCacheMessage", "1024")) / 1024;
        int ramWiki    = Integer.parseInt(getConfig("ramCacheWiki", "1024")) / 1024;
        log.logSystem("LURL    Cache memory = " + ppRamString(ramLURL));
        log.logSystem("NURL    Cache memory = " + ppRamString(ramNURL));
        log.logSystem("EURL    Cache memory = " + ppRamString(ramEURL));
        log.logSystem("RWI     Cache memory = " + ppRamString(ramRWI));
        log.logSystem("HTTP    Cache memory = " + ppRamString(ramHTTP));
        log.logSystem("Message Cache memory = " + ppRamString(ramMessage));
        log.logSystem("Wiki    Cache memory = " + ppRamString(ramWiki));
        
	// make crawl profiles database and default profiles
        log.logSystem("Initializing Crawl Profiles");
        profiles = new plasmaCrawlProfile(new File(plasmaPath, "crawlProfiles0.db"));
        initProfiles();
        
        // start indexing management
        log.logSystem("Starting Indexing Management");
        urlPool = new plasmaURLPool(plasmaPath, ramLURL, ramNURL, ramEURL);
        

        wordIndex = new plasmaWordIndex(plasmaPath, ramRWI, log);
        int wordCacheMax = Integer.parseInt((String) getConfig("wordCacheMax", "10000"));
        wordIndex.setMaxWords(wordCacheMax);
        searchManager = new plasmaSearch(urlPool.loadedURL, wordIndex);
        
        // start a cache manager
        log.logSystem("Starting HT Cache Manager");
        File htCachePath = new File(getRootPath(), getConfig("proxyCache","HTCACHE"));
        long maxCacheSize = 1024 * 1024 * Long.parseLong(getConfig("proxyCacheSize", "2")); // this is megabyte
        this.cacheManager = new plasmaHTCache(htCachePath, maxCacheSize, ramHTTP);
        
        // make parser
        log.logSystem("Starting Parser");
        this.parser = new plasmaParser();
        
        // initialize switchboard queue
        sbQueue = new plasmaSwitchboardQueue(this.cacheManager, urlPool.loadedURL, new File(plasmaPath, "switchboardQueue1.stack"), 10, profiles);
        
        // define an extension-blacklist
        log.logSystem("Parser: Initializing Extension Mappings for Media/Parser");
        plasmaParser.initMediaExt(plasmaParser.extString2extList(getConfig("mediaExt","")));
        plasmaParser.initSupportedRealtimeFileExt(plasmaParser.extString2extList(getConfig("parseableExt","")));

        // define a realtime parsable mimetype list
        log.logSystem("Parser: Initializing Mime Types");
        plasmaParser.initRealtimeParsableMimeTypes(getConfig("parseableRealtimeMimeTypes","application/xhtml+xml,text/html,text/plain"));
        plasmaParser.initParseableMimeTypes(getConfig("parseableMimeTypes",null));
        
        // start a loader
        log.logSystem("Starting Crawl Loader");
        int remoteport;
        try { remoteport = Integer.parseInt(getConfig("remoteProxyPort","3128")); }
        catch (NumberFormatException e) { remoteport = 3128; }
        this.cacheLoader = new plasmaCrawlLoader(this.cacheManager, this.log,
                Integer.parseInt(getConfig("clientTimeout", "10000")),
                5000, crawlSlots,
                getConfig("remoteProxyUse","false").equals("true"),
                getConfig("remoteProxyHost",""),
                remoteport);

	// init boards
        log.logSystem("Starting Message Board");
	messageDB = new messageBoard(new File(getRootPath(), "DATA/SETTINGS/message.db"), ramMessage);
	log.logSystem("Starting Wiki Board");
        wikiDB = new wikiBoard(new File(getRootPath(), "DATA/SETTINGS/wiki.db"),
			       new File(getRootPath(), "DATA/SETTINGS/wiki-bkp.db"), ramWiki);

        // init cookie-Monitor
        log.logSystem("Starting Cookie Monitor");
        outgoingCookies = new HashMap();
        incomingCookies = new HashMap();
            
        // clean up profiles
        log.logSystem("Cleaning Profiles");
        cleanProfiles();

        // init facility DB
        /*
        log.logSystem("Starting Facility Database");
        File facilityDBpath = new File(getRootPath(), "DATA/SETTINGS/");
        facilityDB = new kelondroTables(facilityDBpath);
        facilityDB.declareMaps("backlinks", 250, 500, new String[] {"date"}, null);
        log.logSystem("..opened backlinks");
        facilityDB.declareMaps("zeitgeist",  40, 500);
        log.logSystem("..opened zeitgeist");
        facilityDB.declareTree("statistik", new int[]{11, 8, 8, 8, 8, 8, 8}, 0x400);
        log.logSystem("..opened statistik");
        facilityDB.update("statistik", (new serverDate()).toShortString(false).substring(0, 11), new long[]{1,2,3,4,5,6});
        long[] testresult = facilityDB.selectLong("statistik", "yyyyMMddHHm");
        testresult = facilityDB.selectLong("statistik", (new serverDate()).toShortString(false).substring(0, 11));
        */
        
        // generate snippets cache
        log.logSystem("Initializing Snippet Cache");
        snippetCache = new plasmaSnippetCache(cacheManager, parser,
                                              remoteProxyHost, remoteProxyPort, remoteProxyUse,
                                              log);
        
        // start yacy core
        log.logSystem("Starting YaCy Protocol Core");
        //try{Thread.currentThread().sleep(5000);} catch (InterruptedException e) {} // for profiler
        yacyCore yc = new yacyCore(this);
        //log.logSystem("Started YaCy Protocol Core");
        //System.gc(); try{Thread.currentThread().sleep(5000);} catch (InterruptedException e) {} // for profiler
        serverInstantThread.oneTimeJob(yc, "loadSeeds", yc.log, 3000);
        
        // deploy threads
        log.logSystem("Starting Threads");
        System.gc(); // help for profiler
        int indexing_cluster = Integer.parseInt(getConfig("80_indexing_cluster", "1"));
        if (indexing_cluster < 1) indexing_cluster = 1;
        deployThread("90_cleanup", "Cleanup", "simple cleaning process for monitoring information" ,
                     new serverInstantThread(this, "cleanupJob", "cleanupJobSize"), 10000); // all 5 Minutes
        deployThread("80_indexing", "Parsing/Indexing", "thread that performes document parsing and indexing" ,
                     new serverInstantThread(this, "deQueue", "queueSize"), 10000);
        
        for (int i = 1; i < indexing_cluster; i++) {
            setConfig((i + 80) + "_indexing_idlesleep", getConfig("80_indexing_idlesleep", ""));
            setConfig((i + 80) + "_indexing_busysleep", getConfig("80_indexing_busysleep", ""));
            deployThread((i + 80) + "_indexing", "Parsing/Indexing (cluster job)", "thread that performes document parsing and indexing" ,
                     new serverInstantThread(this, "deQueue", "queueSize"), 10000 + (i * 1000));
        }
        deployThread("70_cachemanager", "Proxy Cache Enqueue", "job takes new proxy files from RAM stack, stores them, and hands over to the Indexing Stack",
                     new serverInstantThread(this, "htEntryStoreJob", "htEntrySize"), 10000);
        deployThread("62_remotetriggeredcrawl", "Remote Crawl Job", "thread that performes a single crawl/indexing step triggered by a remote peer",
                     new serverInstantThread(this, "remoteTriggeredCrawlJob", "remoteTriggeredCrawlJobSize"), 30000);
        deployThread("61_globalcrawltrigger", "Global Crawl Trigger", "thread that triggeres remote peers for crawling",
                   new serverInstantThread(this, "limitCrawlTriggerJob", "limitCrawlTriggerJobSize"), 30000); // error here?
        deployThread("50_localcrawl", "Local Crawl", "thread that performes a single crawl step from the local crawl queue",
                     new serverInstantThread(this, "coreCrawlJob", "coreCrawlJobSize"), 10000);
        deployThread("40_peerseedcycle", "Seed-List Upload", "task that a principal peer performes to generate and upload a seed-list to a ftp account",
                     new serverInstantThread(yc, "publishSeedList", null), 180000);
        serverInstantThread peerPing = null;
        deployThread("30_peerping", "YaCy Core", "this is the p2p-control and peer-ping task",
                     peerPing = new serverInstantThread(yc, "peerPing", null), 2000);
        peerPing.setSyncObject(new Object());
        indexDistribution = new distributeIndex(100 /*indexCount*/, 8000, 1 /*peerCount*/);
        deployThread("20_dhtdistribution", "DHT Distribution (currently by juniors only)", "selection, transfer and deletion of index entries that are not searched on your peer, but on others",
                     new serverInstantThread(indexDistribution, "job", null), 120000);
            
        // init migratiion from 0.37 -> 0.38
        classicCache = new plasmaWordIndexClassicCacheMigration(plasmaPath, wordIndex);
        
        if (classicCache.size() > 0) {
            setConfig("99_indexcachemigration_idlesleep" , 10000);
            setConfig("99_indexcachemigration_busysleep" , 40);
            deployThread("99_indexcachemigration", "index cache migration", "migration of index cache data structures 0.37 -> 0.38",
            new serverInstantThread(classicCache, "oneStepMigration", "size"), 30000);
        }
        
        // test routine for snippet fetch
        //Set query = new HashSet();
        //query.add(plasmaWordIndexEntry.word2hash("Weitergabe"));
        //query.add(plasmaWordIndexEntry.word2hash("Zahl"));
        //plasmaSnippetCache.result scr = snippetCache.retrieve(new URL("http://www.heise.de/mobil/newsticker/meldung/mail/54980"), query, true);
        //plasmaSnippetCache.result scr = snippetCache.retrieve(new URL("http://www.heise.de/security/news/foren/go.shtml?read=1&msg_id=7301419&forum_id=72721"), query, true);
        //plasmaSnippetCache.result scr = snippetCache.retrieve(new URL("http://www.heise.de/kiosk/archiv/ct/2003/4/20"), query, true, 260);

    
    }
    
    private static String ppRamString(int bytes) {
        if (bytes < 1024) return bytes + " KByte";
        bytes = bytes / 1024;
        if (bytes < 1024) return bytes + " MByte";
        bytes = bytes / 1024;
        if (bytes < 1024) return bytes + " GByte";
        return (bytes / 1024) + "TByte";
    }
    
    private void initProfiles() throws IOException {
        if ((profiles.size() == 0) ||
            (getConfig("defaultProxyProfile", "").length() == 0) ||
            (profiles.getEntry(getConfig("defaultProxyProfile", "")) == null)) {
            // generate new default entry for proxy crawling
            defaultProxyProfile = profiles.newEntry("proxy", "", ".*", ".*", Integer.parseInt(getConfig("proxyPrefetchDepth", "0")), Integer.parseInt(getConfig("proxyPrefetchDepth", "0")), false, true, true, true, false, true, true, true);
            setConfig("defaultProxyProfile", defaultProxyProfile.handle());
        } else {
            defaultProxyProfile = profiles.getEntry(getConfig("defaultProxyProfile", ""));
        }
        if ((profiles.size() == 1) ||
            (getConfig("defaultRemoteProfile", "").length() == 0) ||
            (profiles.getEntry(getConfig("defaultRemoteProfile", "")) == null)) {
            // generate new default entry for proxy crawling
            defaultRemoteProfile = profiles.newEntry("remote", "", ".*", ".*", 0, 0, false, false, true, true, false, true, true, false);
            setConfig("defaultRemoteProfile", defaultRemoteProfile.handle());
        } else {
            defaultRemoteProfile = profiles.getEntry(getConfig("defaultRemoteProfile", ""));
        }
    }
    private void resetProfiles() {
        File pdb = new File(plasmaPath, "crawlProfiles0.db");
        if (pdb.exists()) pdb.delete();
        try {
            profiles = new plasmaCrawlProfile(pdb);
            initProfiles();
        } catch (IOException e) {}
    }
    private void cleanProfiles() {
        if ((sbQueue.size() > 0) || (cacheLoader.size() > 0) || (urlPool.noticeURL.stackSize() > 0)) return;
	Iterator i = profiles.profiles(true);
	plasmaCrawlProfile.entry entry;
        try {
            while (i.hasNext()) {
                entry = (plasmaCrawlProfile.entry) i.next();
                if (!((entry.name().equals("proxy")) || (entry.name().equals("remote")))) i.remove();
            }
        } catch (kelondroException e) {
            resetProfiles();
        }
    }

    public plasmaHTCache getCacheManager() {
	return cacheManager;
    }

    
    
    synchronized public void htEntryStoreEnqueued(plasmaHTCache.Entry entry) throws IOException {
	if (cacheManager.full())
	    htEntryStoreProcess(entry);
	else
	    cacheManager.push(entry);
    }
	
    synchronized public boolean htEntryStoreProcess(plasmaHTCache.Entry entry) throws IOException {
        
        if (entry == null) return false;
        
        // store response header
        if ((entry.status == plasmaHTCache.CACHE_FILL) ||
                (entry.status == plasmaHTCache.CACHE_STALE_RELOAD_GOOD) ||
                (entry.status == plasmaHTCache.CACHE_STALE_RELOAD_BAD)) {
            cacheManager.storeHeader(entry.nomalizedURLHash, entry.responseHeader);
        }
        
        // work off unwritten files and undone parsing
        String storeError = null;
        if (((entry.status == plasmaHTCache.CACHE_FILL) || (entry.status == plasmaHTCache.CACHE_STALE_RELOAD_GOOD)) &&
                ((storeError = entry.shallStoreCache()) == null)) {
            
            // write file if not written yet
            if (entry.cacheArray != null)  {
                cacheManager.writeFile(entry.url, entry.cacheArray);
                log.logInfo("WRITE FILE (" + entry.cacheArray.length + " bytes) " + entry.cacheFile);
            }
            // enqueue for further crawling
            enQueue(sbQueue.newEntry(entry.url, plasmaURL.urlHash(entry.referrerURL()),
                    entry.requestHeader.ifModifiedSince(), entry.requestHeader.containsKey(httpHeader.COOKIE),
                    entry.initiator(), entry.depth, entry.profile.handle(),
                    entry.name()
                    ));
        } else if (entry.status == plasmaHTCache.CACHE_PASSING) {
            // even if the file should not be stored in the cache, it can be used to be indexed
            if (storeError != null) log.logDebug("NOT STORED " + entry.cacheFile + ":" + storeError);
            
            // enqueue for further crawling
            enQueue(sbQueue.newEntry(entry.url, plasmaURL.urlHash(entry.referrerURL()),
                    entry.requestHeader.ifModifiedSince(), entry.requestHeader.containsKey(httpHeader.COOKIE),
                    entry.initiator(), entry.depth, entry.profile.handle(),
                    entry.name()
                    ));
        }
        
        // write log
        
        switch (entry.status) {
            case plasmaHTCache.CACHE_UNFILLED:
                log.logInfo("CACHE UNFILLED: " + entry.cacheFile); break;
            case plasmaHTCache.CACHE_FILL:
                log.logInfo("CACHE FILL: " + entry.cacheFile + ((entry.cacheArray == null) ? "" : " (cacheArray is filled)"));
                break;
            case plasmaHTCache.CACHE_HIT:
                log.logInfo("CACHE HIT: " + entry.cacheFile); break;
            case plasmaHTCache.CACHE_STALE_NO_RELOAD:
                log.logInfo("CACHE STALE, NO RELOAD: " + entry.cacheFile); break;
            case plasmaHTCache.CACHE_STALE_RELOAD_GOOD:
                log.logInfo("CACHE STALE, NECESSARY RELOAD: " + entry.cacheFile); break;
            case plasmaHTCache.CACHE_STALE_RELOAD_BAD:
                log.logInfo("CACHE STALE, SUPERFLUOUS RELOAD: " + entry.cacheFile); break;
            case plasmaHTCache.CACHE_PASSING:
                log.logInfo("PASSING: " + entry.cacheFile); break;
            default:
                log.logInfo("CACHE STATE UNKNOWN: " + entry.cacheFile); break;
        }
        return true;
    }
    

    public boolean htEntryStoreJob() {
        if (cacheManager.empty()) return false;
        try {
            return htEntryStoreProcess(cacheManager.pop());
        } catch (IOException e) {
            return false;
        }
    }
    
    public int htEntrySize() {
        return cacheManager.size();
    }
    
    private static TreeSet loadList(File file) {
        TreeSet list = new TreeSet(kelondroMSetTools.fastStringComparator);
        if (!(file.exists())) return list;
        
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if ((line.length() > 0) && (!(line.startsWith("#")))) list.add(line.trim().toLowerCase());
            }
            br.close();
        } catch (IOException e) {            
        } finally {
            if (br != null) try{br.close();}catch(Exception e){}
        }
        return list;
    }

    public void close() {
        log.logSystem("SWITCHBOARD SHUTDOWN STEP 1: sending termination signal to managed threads:");
        terminateAllThreads(true);
        log.logSystem("SWITCHBOARD SHUTDOWN STEP 2: sending termination signal to threaded indexing (stand by..)");
        int waitingBoundSeconds = Integer.parseInt(getConfig("maxWaitingWordFlush", "120"));
        wordIndex.close(waitingBoundSeconds);
        log.logSystem("SWITCHBOARD SHUTDOWN STEP 3: sending termination signal to database manager");
        try {
            cacheLoader.close();
            wikiDB.close();
            messageDB.close();
            if (facilityDB != null) facilityDB.close();
            urlPool.close();
            profiles.close();
            parser.close();            
            cacheManager.close();
            sbQueue.close();
	} catch (IOException e) {}
        log.logSystem("SWITCHBOARD SHUTDOWN TERMINATED");
    }
    
    public int queueSize() {
        return sbQueue.size();
        //return processStack.size() + cacheLoader.size() + noticeURL.stackSize();
    }
    
    public int cacheSizeMin() {
	return wordIndex.size();
    }

    public void enQueue(Object job) {
        if (!(job instanceof plasmaSwitchboardQueue.Entry)) {
            System.out.println("internal error at plasmaSwitchboard.enQueue: wrong job type");
            System.exit(0);
        }
        try {
            sbQueue.push((plasmaSwitchboardQueue.Entry) job);
        } catch (IOException e) {
            log.logError("IOError in plasmaSwitchboard.enQueue: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean deQueue() {
	// work off fresh entries from the proxy or from the crawler

        plasmaSwitchboardQueue.Entry nextentry;
        synchronized (sbQueue) {
            if (sbQueue.size() == 0) {
                //log.logDebug("DEQUEUE: queue is empty");
                return false; // nothing to do
            }
            
            // in case that the server is very busy we do not work off the queue too fast
            if (!(cacheManager.idle())) try {Thread.currentThread().sleep(1000);} catch (InterruptedException e) {}
            
            // do one processing step
            log.logDebug("DEQUEUE: cacheManager=" + ((cacheManager.idle()) ? "idle" : "busy") +
            ", sbQueueSize=" + sbQueue.size() +
            ", coreStackSize=" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE) +
            ", limitStackSize=" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_LIMIT) +
            ", overhangStackSize=" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_OVERHANG) +
            ", remoteStackSize=" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_REMOTE));
            try {
                nextentry = sbQueue.pop();
            } catch (IOException e) {
                log.logError("IOError in plasmaSwitchboard.deQueue: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }
	processResourceStack(nextentry);
	return true;
    }
    
    public int cleanupJobSize() {
        int c = 0;
        if ((urlPool.errorURL.stackSize() > 1000)) c++;
        for (int i = 1; i <= 6; i++) {
	    if (urlPool.loadedURL.getStackSize(i) > 1000) c++;
	}
        return c;
    }
    
    public boolean cleanupJob() {

        boolean hasDoneSomething = false;
        
        // clean up error stack
	if ((urlPool.errorURL.stackSize() > 1000)) {
	    urlPool.errorURL.clearStack();
            hasDoneSomething = true;
	}
	// clean up loadedURL stack
	for (int i = 1; i <= 6; i++) {
	    if (urlPool.loadedURL.getStackSize(i) > 1000) {
		urlPool.loadedURL.clearStack(i);
                hasDoneSomething = true;
	    }
	}
        // clean up profiles
        cleanProfiles();
        return hasDoneSomething;
    }
    
    /**
     * With this function the crawling process can be paused 
     */
    public void pauseCrawling() {
        synchronized(this.crawlingPausedSync) {
            this.crawlingIsPaused = true;
        }
    }
    
    /**
     * Continue the previously paused crawling 
     */
    public void continueCrawling() {
        synchronized(this.crawlingPausedSync) {            
            if (this.crawlingIsPaused) {
                this.crawlingIsPaused = false;
                this.crawlingPausedSync.notifyAll();
            }
        }
    }
    
    /**
     * @return <code>true</code> if crawling was paused or <code>false</code> otherwise
     */
    public boolean crawlingIsPaused() {
        synchronized(this.crawlingPausedSync) {
            return this.crawlingIsPaused;
        }
    }    
    
    public int coreCrawlJobSize() {
        return urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE);
    }
    
    public boolean coreCrawlJob() {
        if (urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE) == 0) {
            //log.logDebug("CoreCrawl: queue is empty");
            return false;
        }
        if (sbQueue.size() >= indexingSlots) {
            log.logDebug("CoreCrawl: too many processes in indexing queue, dismissed (" +
                    "sbQueueSize=" + sbQueue.size() + ")");
            return false;
        }
        if (cacheLoader.size() >= crawlSlots) {
            log.logDebug("CoreCrawl: too many processes in loader queue, dismissed (" +
                    "cacheLoader=" + cacheLoader.size() + ")");
            return false;
        }
        
        // if the server is busy, we do crawling more slowly
        //if (!(cacheManager.idle())) try {Thread.currentThread().sleep(2000);} catch (InterruptedException e) {}
        
        // if crawling was paused we have to wait until we wer notified to continue
        synchronized(this.crawlingPausedSync) {
            if (this.crawlingIsPaused) {
                try {
                    this.crawlingPausedSync.wait();
                }
                catch (InterruptedException e){ return false;}
            }
        }           
        
        // do a local crawl
        plasmaCrawlNURL.entry urlEntry = urlPool.noticeURL.pop(plasmaCrawlNURL.STACK_TYPE_CORE);
        String stats = "LOCALCRAWL[" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_LIMIT) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_OVERHANG) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_REMOTE) + "]";
        if (urlEntry.url() == null) {
            log.logError(stats + ": urlEntry.url() == null");
            return true;
        }
        String profileHandle = urlEntry.profileHandle();
        //System.out.println("DEBUG plasmaSwitchboard.processCrawling: profileHandle = " + profileHandle + ", urlEntry.url = " + urlEntry.url());
        plasmaCrawlProfile.entry profile = profiles.getEntry(profileHandle);
        if (profile == null) {
            log.logError(stats + ": LOST PROFILE HANDLE '" + urlEntry.profileHandle() + "' (must be internal error) for URL " + urlEntry.url());
            return true;
        }
        log.logDebug("LOCALCRAWL: url=" + urlEntry.url() + ", initiator=" + urlEntry.initiator() + 
		     ", crawlOrder=" + ((profile.remoteIndexing()) ? "true" : "false") + ", depth=" + urlEntry.depth() + ", crawlDepth=" + profile.generalDepth() + ", filter=" + profile.generalFilter() +
		     ", permission=" + ((yacyCore.seedDB == null) ? "undefined" : (((yacyCore.seedDB.mySeed.isSenior()) || (yacyCore.seedDB.mySeed.isPrincipal())) ? "true" : "false")));

        processLocalCrawling(urlEntry, profile, stats);
        return true;
    }
    
    public int limitCrawlTriggerJobSize() {
        return urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_LIMIT);
    }
    
    public boolean limitCrawlTriggerJob() {
        if (urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_LIMIT) == 0) {
            //log.logDebug("LimitCrawl: queue is empty");
            return false;
        }
        // if the server is busy, we do crawling more slowly
        //if (!(cacheManager.idle())) try {Thread.currentThread().sleep(2000);} catch (InterruptedException e) {}
        
        // if crawling was paused we have to wait until we wer notified to continue
        synchronized(this.crawlingPausedSync) {
            if (this.crawlingIsPaused) {
                try {
                    this.crawlingPausedSync.wait();
                }
                catch (InterruptedException e){ return false;}
            }
        }           
        
        // start a global crawl, if possible
        plasmaCrawlNURL.entry urlEntry = urlPool.noticeURL.pop(plasmaCrawlNURL.STACK_TYPE_LIMIT);
        String stats = "REMOTECRAWLTRIGGER[" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_LIMIT) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_OVERHANG) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_REMOTE) + "]";
        if (urlEntry.url() == null) {
            log.logError(stats + ": urlEntry.url() == null");
            return true;
        }
        String profileHandle = urlEntry.profileHandle();
        //System.out.println("DEBUG plasmaSwitchboard.processCrawling: profileHandle = " + profileHandle + ", urlEntry.url = " + urlEntry.url());
        plasmaCrawlProfile.entry profile = profiles.getEntry(profileHandle);
        if (profile == null) {
            log.logError(stats + ": LOST PROFILE HANDLE '" + urlEntry.profileHandle() + "' (must be internal error) for URL " + urlEntry.url());
            return true;
        }
        log.logDebug("plasmaSwitchboard.limitCrawlTriggerJob: url=" + urlEntry.url() + ", initiator=" + urlEntry.initiator() + 
		     ", crawlOrder=" + ((profile.remoteIndexing()) ? "true" : "false") + ", depth=" + urlEntry.depth() + ", crawlDepth=" + profile.generalDepth() + ", filter=" + profile.generalFilter() +
		     ", permission=" + ((yacyCore.seedDB == null) ? "undefined" : (((yacyCore.seedDB.mySeed.isSenior()) || (yacyCore.seedDB.mySeed.isPrincipal())) ? "true" : "false")));

        boolean tryRemote = 
            ((urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE) != 0) || (sbQueue.size() != 0)) /* should do ourself */ &&
            (profile.remoteIndexing()) /* granted */ &&
            (urlEntry.initiator() != null) && (!(urlEntry.initiator().equals(plasmaURL.dummyHash))) /* not proxy */ &&
            ((yacyCore.seedDB.mySeed.isSenior()) ||
             (yacyCore.seedDB.mySeed.isPrincipal())) /* qualified */;
                
        if (tryRemote) {
            boolean success = processRemoteCrawlTrigger(urlEntry);
            if (success) return true;
        }
        
        // alternatively do a local crawl
        if (sbQueue.size() >= indexingSlots) {
            log.logDebug("LimitCrawl: too many processes in indexing queue, dismissed (" +
                    "sbQueueSize=" + sbQueue.size() + ")");
            return false;
        }
        if (cacheLoader.size() >= crawlSlots) {
            log.logDebug("LimitCrawl: too many processes in loader queue, dismissed (" +
                    "cacheLoader=" + cacheLoader.size() + ")");
            return false;
        }
        
        processLocalCrawling(urlEntry, profile, stats);
        return true;
    }
    
    public int remoteTriggeredCrawlJobSize() {
        return urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_REMOTE);
    }
    
    public boolean remoteTriggeredCrawlJob() {
        // work off crawl requests that had been placed by other peers to our crawl stack
        
        // do nothing if either there are private processes to be done
        // or there is no global crawl on the stack
        if (urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_REMOTE) == 0) {
            //log.logDebug("GlobalCrawl: queue is empty");
            return false;
        }
        /*
        if (queueStack.size() > 0) {
            log.logDebug("GlobalCrawl: any processe is in queue, dismissed (" +
                    "processStack=" + queueStack.size() + ")");
            return false;
        }
        if (noticeURL.coreStackSize() > 0) {
            log.logDebug("GlobalCrawl: any local crawl is in queue, dismissed (" +
                    "coreStackSize=" + noticeURL.coreStackSize() + ")");
            return false;
        }
        */
        
        // if the server is busy, we do this more slowly
        //if (!(cacheManager.idle())) try {Thread.currentThread().sleep(2000);} catch (InterruptedException e) {}
        
        // if crawling was paused we have to wait until we wer notified to continue
        synchronized(this.crawlingPausedSync) {
            if (this.crawlingIsPaused) {
                try {
                    this.crawlingPausedSync.wait();
                }
                catch (InterruptedException e){ return false; }
            }
        }           
        
        // we don't want to crawl a global URL globally, since WE are the global part. (from this point of view)
        plasmaCrawlNURL.entry urlEntry = urlPool.noticeURL.pop(plasmaCrawlNURL.STACK_TYPE_REMOTE);
        String stats = "REMOTETRIGGEREDCRAWL[" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_LIMIT) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_OVERHANG) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_REMOTE) + "]";
        if (urlEntry.url() == null) {
            log.logError(stats + ": urlEntry.url() == null");
            return false;
        }
        String profileHandle = urlEntry.profileHandle();
        //System.out.println("DEBUG plasmaSwitchboard.processCrawling: profileHandle = " + profileHandle + ", urlEntry.url = " + urlEntry.url());
        plasmaCrawlProfile.entry profile = profiles.getEntry(profileHandle);
        
        if (profile == null) {
            log.logError(stats + ": LOST PROFILE HANDLE '" + urlEntry.profileHandle() + "' (must be internal error) for URL " + urlEntry.url());
            return false;
        }
        log.logDebug("plasmaSwitchboard.remoteTriggeredCrawlJob: url=" + urlEntry.url() + ", initiator=" + urlEntry.initiator() + 
		     ", crawlOrder=" + ((profile.remoteIndexing()) ? "true" : "false") + ", depth=" + urlEntry.depth() + ", crawlDepth=" + profile.generalDepth() + ", filter=" + profile.generalFilter() +
		     ", permission=" + ((yacyCore.seedDB == null) ? "undefined" : (((yacyCore.seedDB.mySeed.isSenior()) || (yacyCore.seedDB.mySeed.isPrincipal())) ? "true" : "false")));

        processLocalCrawling(urlEntry, profile, stats);
        return true;
    }

    private void processResourceStack(plasmaSwitchboardQueue.Entry entry) {
        // work off one stack entry with a fresh resource (scraped web page)
        try {    
            // we must distinguish the following cases: resource-load was initiated by
            // 1) global crawling: the index is extern, not here (not possible here)
            // 2) result of search queries, some indexes are here (not possible here)
            // 3) result of index transfer, some of them are here (not possible here)
            // 4) proxy-load (initiator is "------------")
            // 5) local prefetch/crawling (initiator is own seedHash)
            // 6) local fetching for global crawling (other known or unknwon initiator)
            int processCase = 0;
            yacySeed initiator = null;
            String initiatorHash = (entry.proxy()) ? plasmaURL.dummyHash : entry.initiator();
            if (initiatorHash.equals(plasmaURL.dummyHash)) {
                // proxy-load
                processCase = 4;
            } else if (initiatorHash.equals(yacyCore.seedDB.mySeed.hash)) {
                // normal crawling
                processCase = 5;
            } else {
                // this was done for remote peer (a global crawl)
                initiator = yacyCore.seedDB.getConnected(initiatorHash);
                processCase = 6;
            }
            
            log.logDebug("processResourceStack processCase=" + processCase +
                    ", depth=" + entry.depth() +
                    ", maxDepth=" + ((entry.profile() == null) ? "null" : "" + entry.profile().generalDepth()) +
                    ", filter=" + ((entry.profile() == null) ? "null" : "" + entry.profile().generalFilter()) +
                    ", initiatorHash=" + initiatorHash +
                    ", responseHeader=" + ((entry.responseHeader() == null) ? "null" : entry.responseHeader().toString()) +
                    ", url=" + entry.url()); // DEBUG
            
            // parse content
            plasmaParserDocument document = null;
            if ((plasmaParser.supportedFileExt(entry.url())) ||
                ((entry.responseHeader() != null) &&
                 (plasmaParser.supportedMimeTypesContains(entry.responseHeader().mime())))) {
                if (entry.cacheFile().exists()) {
                    log.logDebug("(Parser) '" + entry.normalizedURLString() + "' is not parsed yet, parsing now from File");
                    document = parser.parseSource(entry.url(), (entry.responseHeader() == null) ? null : entry.responseHeader().mime(), entry.cacheFile());
                } else {
                    log.logDebug("(Parser) '" + entry.normalizedURLString() + "' cannot be parsed, no resource available");
                    return;
                }
                if (document == null) {
                    log.logError("(Parser) '" + entry.normalizedURLString() + "' parse failure");
                    return;
                }
            } else {
                log.logDebug("(Parser) '" + entry.normalizedURLString() + "'. Unsupported mimeType '" + ((entry.responseHeader() == null) ? null : entry.responseHeader().mime()) + "'.");
                return;                
            }
            
            Date loadDate = null;
            if (entry.responseHeader() != null) {
                loadDate = entry.responseHeader().lastModified();
                if (loadDate == null) loadDate = entry.responseHeader().date();
            }
            if (loadDate == null) loadDate = new Date();
            
            // put anchors on crawl stack
            if (((processCase == 4) || (processCase == 5)) &&
                ((entry.profile() == null) || (entry.depth() < entry.profile().generalDepth()))) {
                Map hl = document.getHyperlinks();
                Iterator i = hl.entrySet().iterator();
                String nexturlstring;
                String rejectReason;
                int c = 0;
                Map.Entry e;
                while (i.hasNext()) {
                    e = (Map.Entry) i.next();
                    nexturlstring = (String) e.getKey();
                    
                    rejectReason = stackCrawl(nexturlstring, entry.normalizedURLString(), initiatorHash, (String) e.getValue(), loadDate, entry.depth() + 1, entry.profile());
                    if (rejectReason == null) {
                        c++;
                    } else {
                        urlPool.errorURL.newEntry(new URL(nexturlstring), entry.normalizedURLString(), entry.initiator(), yacyCore.seedDB.mySeed.hash,
				       (String) e.getValue(), rejectReason, new bitfield(plasmaURL.urlFlagLength), false);
                    }
                }
                log.logInfo("CRAWL: ADDED " + c + " LINKS FROM " + entry.url().toString() +
                            ", NEW CRAWL STACK SIZE IS " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE));
            }
            
            // create index
            String descr = document.getMainLongTitle();
            URL referrerURL = entry.referrerURL();
            String referrerHash = (referrerURL == null) ? plasmaURL.dummyHash : plasmaURL.urlHash(referrerURL);
            String noIndexReason = "unspecified";
            if (processCase == 4) {
                // proxy-load
                noIndexReason = entry.shallIndexCacheForProxy();
            } else {
                // normal crawling
                noIndexReason = entry.shallIndexCacheForCrawler();
            }
            if (noIndexReason == null) {
                // strip out words
                log.logDebug("(Profile) Condensing for '" + entry.normalizedURLString() + "'");
                plasmaCondenser condenser = new plasmaCondenser(new ByteArrayInputStream(document.getText()));
 
                //log.logInfo("INDEXING HEADLINE:" + descr);
                try {
                    log.logDebug("(Profile) Create LURL-Entry for '" + entry.normalizedURLString() + "', " +
                                 "responseHeader=" + entry.responseHeader().toString());
                    Date lastModified = entry.responseHeader().lastModified();
                    if (lastModified == null) lastModified = entry.responseHeader().date();
                    if (lastModified == null) lastModified = new Date();
                    plasmaCrawlLURL.entry newEntry = urlPool.loadedURL.newEntry(
                                        entry.url(), descr, lastModified, new Date(),
                                        initiatorHash,
                                        yacyCore.seedDB.mySeed.hash,
                                        referrerHash,
                                        0, true,
                                        Integer.parseInt(condenser.getAnalysis().getProperty("INFORMATION_VALUE","0"), 16),
                                        plasmaWordIndexEntry.language(entry.url()),
                                        plasmaWordIndexEntry.docType(entry.responseHeader().mime()),
                                        entry.size(),
                                        (int) Long.parseLong(condenser.getAnalysis().getProperty("NUMB_WORDS","0"), 16),
                                        processCase
                                     );
                    
                    String urlHash = newEntry.hash();
                    log.logDebug("(Profile) Remove NURL for '" + entry.normalizedURLString() + "'");
                    urlPool.noticeURL.remove(urlHash); // worked-off
                    
                    if (((processCase == 4) || (processCase == 5) || (processCase == 6)) &&
			(entry.profile().localIndexing())) {
                        // remove stopwords
                        log.logDebug("(Profile) Exclude Stopwords for '" + entry.normalizedURLString() + "'");
                        log.logInfo("Excluded " + condenser.excludeWords(stopwords) + " words in URL " + entry.url());
                        //System.out.println("DEBUG: words left to be indexed: " + condenser.getWords());
                        
                        // do indexing
                        log.logDebug("(Profile) Create Index for '" + entry.normalizedURLString() + "'");
                        int words = searchManager.addPageIndex(entry.url(), urlHash, loadDate, condenser, plasmaWordIndexEntry.language(entry.url()), plasmaWordIndexEntry.docType(entry.responseHeader().mime()));
                        log.logInfo("Indexed " + words + " words in URL " + entry.url() + " (" + descr + ")");
                        
                        // if this was performed for a remote crawl request, notify requester
                        if ((processCase == 6) && (initiator != null)) {
                            log.logInfo("Sending crawl receipt for '" + entry.normalizedURLString() + "' to " + initiator.getName());
                            yacyClient.crawlReceipt(initiator, "crawl", "fill", "indexed", newEntry, "");
                        }
                    } else {
                        log.logDebug("Resource '" + entry.normalizedURLString() + "' not indexed (indexing is off)");
                    }
                } catch (Exception ee) {
                    log.logError("Could not index URL " + entry.url() + ": " + ee.getMessage());
                    ee.printStackTrace();
                    if ((processCase == 6) && (initiator != null)) {
                        yacyClient.crawlReceipt(initiator, "crawl", "exception", ee.getMessage(), null, "");
                    }
                }
                
            } else {
                log.logInfo("Not indexed any word in URL " + entry.url() + "; cause: " + noIndexReason);
                urlPool.errorURL.newEntry(entry.url(), referrerHash,
                                  ((entry.proxy()) ? plasmaURL.dummyHash : entry.initiator()), 
                                  yacyCore.seedDB.mySeed.hash,
                                  descr, noIndexReason, new bitfield(plasmaURL.urlFlagLength), true);
                if ((processCase == 6) && (initiator != null)) {
                    yacyClient.crawlReceipt(initiator, "crawl", "rejected", noIndexReason, null, "");
                }
            }
            
            // explicit delete/free resources
            if ((entry != null) && (entry.profile() != null) && (!(entry.profile().storeHTCache()))) cacheManager.deleteFile(entry.url());
            document = null; entry = null;
            
            
        } catch (IOException e) {
            log.logError("ERROR in plasmaSwitchboard.process(): " + e.toString());
        }
    }

    public String stackCrawl(String nexturlString, String referrerString, String initiatorHash, String name, Date loadDate, int currentdepth, plasmaCrawlProfile.entry profile) {
        // stacks a crawl item. The position can also be remote
        // returns null if successful, a reason string if not successful

        String reason = null; // failure reason

	// strange error
	if (nexturlString == null) {
	    reason = "denied_(url_null)";
            log.logError("Wrong URL in stackCrawl: url=null");
	    return reason;
	}

        URL nexturl = null;
        if ((initiatorHash == null) || (initiatorHash.length() == 0)) initiatorHash = plasmaURL.dummyHash;
        String referrerHash = plasmaURL.urlHash(referrerString);
        try {
            nexturl = new URL(nexturlString);
        } catch (MalformedURLException e) {
            reason = "denied_(url_'" + nexturlString + "'_wrong)";
            log.logError("Wrong URL in stackCrawl: " + nexturlString);
            return reason;
        }
        
        // filter deny
        if ((currentdepth > 0) && (!(nexturlString.matches(profile.generalFilter())))) {
            reason = "denied_(does_not_match_filter)";
            urlPool.errorURL.newEntry(nexturl, referrerHash, initiatorHash, yacyCore.seedDB.mySeed.hash,
                    name, reason, new bitfield(plasmaURL.urlFlagLength), false);
            return reason;
        }
        
        // deny cgi
        if (plasmaHTCache.isCGI(nexturlString))  {
            reason = "denied_(cgi_url)";
            urlPool.errorURL.newEntry(nexturl, referrerHash, initiatorHash, yacyCore.seedDB.mySeed.hash,
                                  name, reason, new bitfield(plasmaURL.urlFlagLength), false);
            return reason;
        }

        // deny post properties
        if ((plasmaHTCache.isPOST(nexturlString)) && (!(profile.crawlingQ())))  {
            reason = "denied_(post_url)";
            urlPool.errorURL.newEntry(nexturl, referrerHash, initiatorHash, yacyCore.seedDB.mySeed.hash,
                                  name, reason, new bitfield(plasmaURL.urlFlagLength), false);
            return reason;
        }

        String nexturlhash = plasmaURL.urlHash(nexturl);
        String dbocc = "";
        if ((dbocc = urlPool.testHash(nexturlhash)) != null) {
            // DISTIGUISH OLD/RE-SEARCH CASES HERE!
            reason = "double_(registered_in_" + dbocc + ")";
            urlPool.errorURL.newEntry(nexturl, referrerHash, initiatorHash, yacyCore.seedDB.mySeed.hash,
                                  name, reason, new bitfield(plasmaURL.urlFlagLength), false);
            return reason;
        }
        
        // store information
        boolean local = ((initiatorHash.equals(plasmaURL.dummyHash)) || (initiatorHash.equals(yacyCore.seedDB.mySeed.hash)));
        boolean global = 
            (profile.remoteIndexing()) /* granted */ &&
            (currentdepth == profile.generalDepth()) /* leaf node */ && 
            (initiatorHash.equals(yacyCore.seedDB.mySeed.hash)) /* not proxy */ &&
            ((yacyCore.seedDB.mySeed.isSenior()) ||
             (yacyCore.seedDB.mySeed.isPrincipal())) /* qualified */;
        
        urlPool.noticeURL.newEntry(initiatorHash, /* initiator, needed for p2p-feedback */
                           nexturl, /* url clear text string */
                           loadDate, /* load date */
                           referrerHash, /* last url in crawling queue */
                           name, /* the anchor name */
                           profile.handle(), 
                           currentdepth, /*depth so far*/
                           0, /*anchors, default value */
                           0, /*forkfactor, default value */
                           ((global) ? plasmaCrawlNURL.STACK_TYPE_LIMIT :
                           ((local) ? plasmaCrawlNURL.STACK_TYPE_CORE : plasmaCrawlNURL.STACK_TYPE_REMOTE)) /*local/remote stack*/
                           );
        
        return null;
    }
    
    private URL hash2url(String urlhash) {
        if (urlhash.equals(plasmaURL.dummyHash)) return null;
        plasmaCrawlNURL.entry ne = urlPool.noticeURL.getEntry(urlhash);
        if (ne != null) return ne.url();
        plasmaCrawlLURL.entry le = urlPool.loadedURL.getEntry(urlhash);
        if (le != null) return le.url();
        plasmaCrawlEURL.entry ee = urlPool.errorURL.getEntry(urlhash);
        if (ee != null) return ee.url();
        return null;
    }
    
    private String hash2urlstring(String urlhash) {
        URL u = hash2url(urlhash);
        if (u == null) return plasmaURL.dummyHash; else return u.toString();
    }
    
    private void processLocalCrawling(plasmaCrawlNURL.entry urlEntry, plasmaCrawlProfile.entry profile, String stats) {
        // work off one Crawl stack entry
        if ((urlEntry == null) && (urlEntry.url() == null)) {
            log.logInfo(stats + ": urlEntry=null");
            return;
        }
        cacheLoader.loadParallel(urlEntry.url(), urlEntry.name(), urlEntry.referrerHash(), urlEntry.initiator(), urlEntry.depth(), profile);
        log.logInfo(stats + ": enqueued for load " + urlEntry.url());
        return;
    }
    
    private boolean processRemoteCrawlTrigger(plasmaCrawlNURL.entry urlEntry) {
        // return true iff another peer has/will index(ed) the url
        if (urlEntry == null) {
            log.logInfo("REMOTECRAWLTRIGGER[" + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_CORE) + ", " + urlPool.noticeURL.stackSize(plasmaCrawlNURL.STACK_TYPE_REMOTE) + "]: urlEntry=null");
            return true; // superfluous request; true correct in this context
        }

        // are we qualified?
        if ((yacyCore.seedDB.mySeed == null) ||
            (yacyCore.seedDB.mySeed.isJunior())) {
            log.logDebug("plasmaSwitchboard.processRemoteCrawlTrigger: no permission");
            return false;
        }

        // check url
        if (urlEntry.url() == null) {
            log.logDebug("ERROR: plasmaSwitchboard.processRemoteCrawlTrigger - url is null. name=" + urlEntry.name());
            return true;
        }
        String nexturlString = urlEntry.url().toString();
        String urlhash = plasmaURL.urlHash(urlEntry.url());
        
        // check remote crawl
        yacySeed remoteSeed = yacyCore.dhtAgent.getCrawlSeed(urlhash);
        
        if (remoteSeed == null) {
            log.logDebug("plasmaSwitchboard.processRemoteCrawlTrigger: no remote crawl seed available");
            return false;            
        }
        
        // do the request
        HashMap page = yacyClient.crawlOrder(remoteSeed, nexturlString, hash2urlstring(urlEntry.referrerHash()), 0);

        
        // check success
        /*
         the result of the 'response' value can have one of the following values:
         negative cases, no retry
           denied      - the peer does not want to crawl that
           exception   - an exception occurred

         negative case, retry possible
           rejected    - the peer has rejected to process, but a re-try should be possible
         
         positive case with crawling
           stacked     - the resource is processed asap
	 
         positive case without crawling	 
           double      - the resource is already in database, believed to be fresh and not reloaded
                         the resource is also returned in lurl
        */
        if ((page == null) || (page.get("delay") == null)) {
            log.logInfo("CRAWL: REMOTE CRAWL TO PEER " + remoteSeed.getName() + " FAILED. CAUSE: unknown (URL=" + nexturlString + ")");
            if (remoteSeed != null) yacyCore.peerActions.peerDeparture(remoteSeed);
            return false;
        } else try {
            log.logDebug("plasmaSwitchboard.processRemoteCrawlTrigger: remoteSeed=" + remoteSeed.getName() + ", url=" + nexturlString + ", response=" + page.toString()); // DEBUG
        
            int newdelay = Integer.parseInt((String) page.get("delay"));
            yacyCore.dhtAgent.setCrawlDelay(remoteSeed.hash, newdelay);
            String response = (String) page.get("response");
            if (response.equals("stacked")) {
                log.logInfo("REMOTECRAWLTRIGGER: REMOTE CRAWL TO PEER " + remoteSeed.getName() + " PLACED URL=" + nexturlString + "; NEW DELAY=" + newdelay);
                return true;
            } else if (response.equals("double")) {
                String lurl = (String) page.get("lurl");
                if ((lurl != null) && (lurl.length() != 0)) {
                    String propStr = crypt.simpleDecode(lurl, (String) page.get("key"));        
                    plasmaCrawlLURL.entry entry = urlPool.loadedURL.newEntry(propStr, true, yacyCore.seedDB.mySeed.hash, remoteSeed.hash, 1);
                    urlPool.noticeURL.remove(entry.hash());
                    log.logInfo("REMOTECRAWLTRIGGER: REMOTE CRAWL TO PEER " + remoteSeed.getName() + " SUPERFLUOUS. CAUSE: " + page.get("reason") + " (URL=" + nexturlString + "). URL IS CONSIDERED AS 'LOADED!'");
                    return true;
                } else {
                    log.logInfo("REMOTECRAWLTRIGGER: REMOTE CRAWL TO PEER " + remoteSeed.getName() + " REJECTED. CAUSE: " + page.get("reason") + " (URL=" + nexturlString + ")");
                    return false;
                }
            } else {
                log.logInfo("REMOTECRAWLTRIGGER: REMOTE CRAWL TO PEER " + remoteSeed.getName() + " DENIED. RESPONSE=" + response + ", CAUSE=" + page.get("reason") + ", URL=" + nexturlString);
                return false;
            }
        } catch (Exception e) {
            // wrong values
            log.logError("REMOTECRAWLTRIGGER: REMOTE CRAWL TO PEER " + remoteSeed.getName() + " FAILED. CLIENT RETURNED: " + page.toString());
            e.printStackTrace();
            return false;
        }
    }

    
    private static SimpleDateFormat DateFormatter = new SimpleDateFormat("EEE, dd MMM yyyy");
    public static String dateString(Date date) {
	if (date == null) return ""; else return DateFormatter.format(date);
    }
    
    public class presearch extends Thread {
        Set queryhashes;
        char[] order;
        String urlmask;
        long time;
        int searchcount, fetchcount;
        public presearch(Set queryhashes, char[] order, long time /*milliseconds*/, String urlmask, int searchcount, int fetchcount) {
            this.queryhashes = queryhashes;
            this.order = order;
            this.urlmask = urlmask;
            this.time = time;
            this.searchcount = searchcount;
            this.fetchcount = fetchcount;
        }
        public void run() {
            try {
                // search the database locally
                log.logDebug("presearch: started job");
                plasmaWordIndexEntity idx = searchManager.searchHashes(queryhashes, time);
                log.logDebug("presearch: found " + idx.size() + " results");
                plasmaSearch.result acc = searchManager.order(idx, queryhashes, stopwords, order, time, searchcount);
                if (acc == null) return;
                log.logDebug("presearch: ordered results, now " + acc.sizeOrdered() + " URLs ready for fetch");
                
                // take some elements and fetch the snippets
                fetchSnippets(acc, queryhashes, urlmask, fetchcount);
            } catch (IOException e) {
                e.printStackTrace();
            }
            log.logDebug("presearch: job terminated");
        }
    }
    
    public void fetchSnippets(plasmaSearch.result acc, Set queryhashes, String urlmask, int fetchcount) {
        // fetch the snippets
        int i = 0;
        plasmaCrawlLURL.entry urlentry;
        String urlstring;
        plasmaSnippetCache.result snippet;
        while ((acc.hasMoreElements()) && (i < fetchcount)) {
            urlentry = acc.nextElement();
            if (urlentry.url().getHost().endsWith(".yacyh")) continue;
            urlstring = htmlFilterContentScraper.urlNormalform(urlentry.url());
            if ((urlstring.matches(urlmask)) &&
                (!(snippetCache.existsInCache(urlentry.url(), queryhashes)))) {
                new snippetFetcher(urlentry.url(), queryhashes).start();
                i++;
            }
        }
    }
        
    public class snippetFetcher extends Thread {
        URL url;
        Set queryhashes;
        public snippetFetcher(URL url, Set queryhashes) {
            if (url.getHost().endsWith(".yacyh")) return;
            this.url = url;
            this.queryhashes = queryhashes;
        }
        public void run() {
            log.logDebug("snippetFetcher: try to get URL " + url);
            plasmaSnippetCache.result snippet = snippetCache.retrieve(url, queryhashes, true, 260);
            if (snippet.line == null)
                log.logDebug("snippetFetcher: cannot get URL " + url + ". error(" + snippet.source + "): " + snippet.error);
            else
                log.logDebug("snippetFetcher: got URL " + url + ", the snippet is '" + snippet.line + "', source=" + snippet.source);
        }
    }
    
    public serverObjects searchFromLocal(Set querywords, String order1, String order2, int count, boolean global, long time /*milliseconds*/, String urlmask) {
        
        serverObjects prop = new serverObjects();
        try {
            char[] order = new char[2];
            if (order1.equals("quality")) order[0] = plasmaSearch.O_QUALITY; else order[0] = plasmaSearch.O_AGE;
            if (order2.equals("quality")) order[1] = plasmaSearch.O_QUALITY; else order[1] = plasmaSearch.O_AGE;
            
            // filter out words that appear in bluelist
            Iterator it = querywords.iterator();
            String word, gs = "";
            while (it.hasNext()) {
                word = (String) it.next();
                if (blueList.contains(word)) it.remove(); else gs += "+" + word;
            }
            if (gs.length() > 0) gs = gs.substring(1);
            Set queryhashes = plasmaSearch.words2hashes(querywords);
            
            // log
            log.logInfo("INIT WORD SEARCH: " + gs + " - " + count + " links, " + (time / 1000) + " seconds");
            long timestamp = System.currentTimeMillis();
            
            // start a presearch, which makes only sense if we idle afterwards.
            // this is especially the case if we start a global search and idle until search
            if (global) {
                Thread preselect = new presearch(queryhashes, order, time / 10, urlmask, 10, 3);
                preselect.start();
            }
            
            // do global fetching
            int globalresults = 0;
            if (global) {
                int fetchcount = ((int) time / 1000) * 4; // number of wanted results until break in search
                int fetchpeers = ((int) time / 1000) * 3; // number of target peers; means 30 peers in 10 seconds
                long fetchtime = time * 7 / 10;           // time to waste
                if (fetchcount > count) fetchcount = count;
                globalresults = yacySearch.searchHashes(queryhashes, urlPool.loadedURL, searchManager, fetchcount, fetchpeers, snippetCache, fetchtime);
                log.logDebug("SEARCH TIME AFTER GLOBAL-TRIGGER TO " + fetchpeers + " PEERS: " + ((System.currentTimeMillis() - timestamp) / 1000) + " seconds");
            }
            prop.put("globalresults", globalresults); // the result are written to the local DB
            
            
            // now search locally (the global results should be now in the local db)
            long remainingTime = time - (System.currentTimeMillis() - timestamp);
            plasmaWordIndexEntity idx = searchManager.searchHashes(queryhashes, remainingTime * 8 / 10); // the search
            log.logDebug("SEARCH TIME AFTER FINDING " + idx.size() + " ELEMENTS: " + ((System.currentTimeMillis() - timestamp) / 1000) + " seconds");
            
            remainingTime = time - (System.currentTimeMillis() - timestamp);
            if (remainingTime < 500) remainingTime = 500;
            if (remainingTime > 3000) remainingTime = 3000;
            plasmaSearch.result acc = searchManager.order(idx, queryhashes, stopwords, order, remainingTime, 10);
            if (!(global)) fetchSnippets(acc.cloneSmart(), queryhashes, urlmask, 10);
            log.logDebug("SEARCH TIME AFTER ORDERING OF SEARCH RESULT: " + ((System.currentTimeMillis() - timestamp) / 1000) + " seconds");
            
            // result is a List of urlEntry elements: prepare answer
            if (acc == null) {
                prop.put("totalcount", "0");
                prop.put("linkcount", "0");
            } else {
                prop.put("totalcount", "" + acc.sizeOrdered());
                int i = 0;
                int p;
                URL url;
                plasmaCrawlLURL.entry urlentry;
                String urlstring, urlname, filename;
                String host, hash, address, descr = "";
                yacySeed seed;
                plasmaSnippetCache.result snippet;
                //kelondroMScoreCluster ref = new kelondroMScoreCluster();
                while ((acc.hasMoreElements()) && (i < count)) {
                    urlentry = acc.nextElement();
                    url = urlentry.url();
                    host = url.getHost();
                    if (host.endsWith(".yacyh")) {
                        // translate host into current IP
                        p = host.indexOf(".");
                        hash = yacySeed.hexHash2b64Hash(host.substring(p + 1, host.length() - 6));
                        seed = yacyCore.seedDB.getConnected(hash);
                        filename = url.getFile();
                        if ((seed == null) || ((address = seed.getAddress()) == null)) {
                            // seed is not known from here
                            removeReferences(urlentry.hash(), plasmaCondenser.getWords(("yacyshare " + filename.replace('?', ' ') + " " + urlentry.descr()).getBytes()));
                            urlPool.loadedURL.remove(urlentry.hash()); // clean up
                            continue; // next result
                        }
                        url = new URL("http://" + address + "/" + host.substring(0, p) + filename);
                        urlname = "http://share." + seed.getName() + ".yacy" + filename;
                        if ((p = urlname.indexOf("?")) > 0) urlname = urlname.substring(0, p);
                        urlstring = htmlFilterContentScraper.urlNormalform(url);
                    } else {
                        urlstring = htmlFilterContentScraper.urlNormalform(url);
                        urlname = urlstring;
                    }
                    descr = urlentry.descr();

                    // check bluelist again: filter out all links where any bluelisted word
                    // appear either in url, url's description or search word
                    // the search word was sorted out earlier
                        /*
                        String s = descr.toLowerCase() + url.toString().toLowerCase();
                        for (int c = 0; c < blueList.length; c++) {
                            if (s.indexOf(blueList[c]) >= 0) return;
                        }
                         */
                    //addScoreForked(ref, gs, descr.split(" "));
                    //addScoreForked(ref, gs, urlstring.split("/"));
                    if (urlstring.matches(urlmask)) { //.* is default
                        snippet = snippetCache.retrieve(url, queryhashes, false, 260);
                        if (snippet.source == plasmaSnippetCache.ERROR_NO_MATCH) {
                            // suppress line: there is no match in that resource
                        } else {
                            prop.put("results_" + i + "_description", descr);
                            prop.put("results_" + i + "_url", urlstring);
                            prop.put("results_" + i + "_urlname", urlname);
                            prop.put("results_" + i + "_date", dateString(urlentry.moddate()));
                            prop.put("results_" + i + "_size", Long.toString(urlentry.size()));
                            if (snippet.line == null) {
                                prop.put("results_" + i + "_snippet", 0);
                                prop.put("results_" + i + "_snippet_text", "");
                            } else {
                                prop.put("results_" + i + "_snippet", 1);
                                prop.put("results_" + i + "_snippet_text", snippet.line);
                            }
                            i++;
                        }
                    }
                }
                log.logDebug("SEARCH TIME AFTER RESULT PREPARATION: " + ((System.currentTimeMillis() - timestamp) / 1000) + " seconds");

                // calc some more cross-reference
                remainingTime = time - (System.currentTimeMillis() - timestamp);
                if (remainingTime < 0) remainingTime = 1000;
                /*
                while ((acc.hasMoreElements()) && (((time + timestamp) < System.currentTimeMillis()))) {
                    urlentry = acc.nextElement();
                    urlstring = htmlFilterContentScraper.urlNormalform(urlentry.url());
                    descr = urlentry.descr();
                    
                    addScoreForked(ref, gs, descr.split(" "));
                    addScoreForked(ref, gs, urlstring.split("/"));
                }
                 **/
                //Object[] ws = ref.getScores(16, false, 2, Integer.MAX_VALUE);
                Object[] ws = acc.getReferences(16);
                log.logDebug("SEARCH TIME AFTER XREF PREPARATION: " + ((System.currentTimeMillis() - timestamp) / 1000) + " seconds");

                    /*
                    System.out.print("DEBUG WORD-SCORE: ");
                    for (int ii = 0; ii < ws.length; ii++) System.out.print(ws[ii] + ", ");
                    System.out.println(" all words = " + ref.getElementCount() + ", total count = " + ref.getTotalCount());
                     */
                prop.put("references", ws);
                prop.put("linkcount", "" + i);
                prop.put("results", "" + i);
            }
            
            // log
            log.logInfo("EXIT WORD SEARCH: " + gs + " - " +
            prop.get("totalcount", "0") + " links, " +
            ((System.currentTimeMillis() - timestamp) / 1000) + " seconds");
            if (idx != null) idx.close();
            return prop;
        } catch (IOException e) {
            return null;
        }
    }
    
    public serverObjects searchFromRemote(Set hashes, int count, boolean global, long duetime) {
        if (hashes == null) hashes = new HashSet();
        
        serverObjects prop = new serverObjects();
        try {
            log.logInfo("INIT HASH SEARCH: " + hashes + " - " + count + " links");
            long timestamp = System.currentTimeMillis();
            plasmaWordIndexEntity idx = searchManager.searchHashes(hashes, duetime * 8 / 10); // a nameless temporary index, not sorted by special order but by hash
            long remainingTime = duetime - (System.currentTimeMillis() - timestamp);
            plasmaSearch.result acc = searchManager.order(idx, hashes, stopwords, new char[]{plasmaSearch.O_QUALITY, plasmaSearch.O_AGE}, remainingTime, 10);
            
            // result is a List of urlEntry elements
            if (acc == null) {
                prop.put("totalcount", "0");
                prop.put("linkcount", "0");
                prop.put("references", "");
            } else {
                prop.put("totalcount", "" + acc.sizeOrdered());
                int i = 0;
                StringBuffer links = new StringBuffer();
                String resource = "";
                //plasmaIndexEntry pie;
                plasmaCrawlLURL.entry urlentry;
                plasmaSnippetCache.result snippet;
                while ((acc.hasMoreElements()) && (i < count)) {
                    urlentry = acc.nextElement();
                    snippet = snippetCache.retrieve(urlentry.url(), hashes, false, 260);
                    if (snippet.source == plasmaSnippetCache.ERROR_NO_MATCH) {
                        // suppress line: there is no match in that resource
                    } else {
                        if (snippet.line == null) {
                            resource = urlentry.toString();
                        } else {
                            resource = urlentry.toString(snippet.line);
                        }
                        if (resource != null) {
                            links.append("resource").append(i).append("=").append(resource).append(serverCore.crlfString);
                            i++;
                        }
                    }
                }
                prop.put("links", links.toString());
                prop.put("linkcount", "" + i);
                
                // prepare reference hints
                Object[] ws = acc.getReferences(16);
                StringBuffer refstr = new StringBuffer();
                for (int j = 0; j < ws.length; j++) refstr.append(",").append((String) ws[j]);
                prop.put("references", (refstr.length() > 0)?refstr.substring(1):refstr.toString());
            }
            
            // add information about forward peers
            prop.put("fwhop", ""); // hops (depth) of forwards that had been performed to construct this result
            prop.put("fwsrc", ""); // peers that helped to construct this result
            prop.put("fwrec", ""); // peers that would have helped to construct this result (recommendations)
            
            // log
            log.logInfo("EXIT HASH SEARCH: " + hashes + " - " +
            ((idx == null) ? "0" : (""+idx.size())) + " links, " +
            ((System.currentTimeMillis() - timestamp) / 1000) + " seconds");
            if (idx != null) idx.close();
            return prop;
        } catch (IOException e) {
            return null;
        }
    }
    
    
    public serverObjects action(String actionName, serverObjects actionInput) {
	// perform an action.

	if (actionName.equals("urlcount")) {
	    serverObjects result = new serverObjects();
	    result.put("urls","" + urlPool.loadedURL.size());
	    return result;
	}

	// not a correct query
	return null;
    }


    public String toString() {
	// it is possible to use this method in the cgi pages.
	// actually it is used there for testing purpose
	return "PROPS: " + super.toString() + "; QUEUE: " + sbQueue.toString();
    }
    
    // method for index deletion
    public int removeAllUrlReferences(URL url, boolean fetchOnline) {
        return removeAllUrlReferences(plasmaURL.urlHash(url), fetchOnline);
    }
    
    public int removeAllUrlReferences(String urlhash, boolean fetchOnline) {
        // find all the words in a specific resource and remove the url reference from every word index
        // finally, delete the url entry
        
        // determine the url string
        plasmaCrawlLURL.entry entry = urlPool.loadedURL.getEntry(urlhash);
        URL url = entry.url();
        if (url == null) return 0;
        // get set of words
        //Set words = plasmaCondenser.getWords(getText(getResource(url, fetchOnline)));
        Set words = plasmaCondenser.getWords(snippetCache.parseDocument(url, snippetCache.getResource(url, fetchOnline)).getText());
        // delete all word references
        int count = removeReferences(urlhash, words);
        // finally delete the url entry itself
        urlPool.loadedURL.remove(urlhash);
        return count;
    }
    
    public int removeReferences(URL url, Set words) {
        return removeReferences(plasmaURL.urlHash(url), words);
    }
    
    public int removeReferences(String urlhash, Set words) {
        // sequentially delete all word references
        // returns number of deletions
        Iterator it = words.iterator();
        String word;
        String[] urlEntries = new String[] {urlhash};
        int count = 0;
        while (it.hasNext()) {
            word = (String) it.next();
            // delete the URL reference in this word index
            count += wordIndex.removeEntries(plasmaWordIndexEntry.word2hash(word), urlEntries, true);
        }
        return count;
    }

    public class distributeIndex {
        // distributes parts of the index to other peers
        // stops as soon as an error occurrs
 
        int indexCount;
        int peerCount;
        long pause;
        long maxTime;
        
	public distributeIndex(int indexCount, long maxTimePerTransfer, int peerCount) {
           this.indexCount = indexCount;
            this.peerCount = peerCount;
            this.maxTime = maxTimePerTransfer;
	}

	public boolean job() {
            if ((yacyCore.seedDB == null) ||
                (yacyCore.seedDB.mySeed == null) ||
                (yacyCore.seedDB.mySeed.isVirgin()) ||
                (urlPool.loadedURL.size() < 10) ||
                (wordIndex.size() < 100) ||
                (!(yacyCore.seedDB.mySeed.isJunior()))) return false;

            int transferred;
            long starttime = System.currentTimeMillis();
            try {
                if (
                (sbQueue.size() == 0) &&
                (cacheLoader.size() == 0) &&
                (urlPool.noticeURL.stackSize() == 0) &&
                (getConfig("allowDistributeIndex", "false").equals("true")) &&
                ((transferred = performTransferIndex(indexCount, peerCount, true)) > 0)) {
                    indexCount = transferred;
                    if ((System.currentTimeMillis() - starttime) > (maxTime * peerCount)) indexCount--; else indexCount++;
                    if (indexCount < 30) indexCount = 30;
                    return true;
                } else {
                    // make a long pause
                    return false;
                }
            } catch (IllegalArgumentException ee) {
                // this is a bug that occurres if a not-fixeable data-inconsistency in the table structure was detected
                // make a long pause
                log.logError("very bad data inconsistency: " + ee.getMessage());
                //ee.printStackTrace();
                return false;
            }
	}

        public void setCounts(int indexCount, int peerCount, long pause) {
            this.indexCount = indexCount;
            if (indexCount < 30) indexCount = 30;
            this.peerCount = peerCount;
            this.pause = pause;
        }
        
    }

    public int performTransferIndex(int indexCount, int peerCount, boolean delete) {
	if ((yacyCore.seedDB == null) || (yacyCore.seedDB.sizeConnected() == 0)) return -1;

        // collect index
        //String startPointHash = yacyCore.seedCache.mySeed.hash;
        String startPointHash = serverCodings.encodeMD5B64("" + System.currentTimeMillis(), true).substring(0, yacySeedDB.commonHashLength);
        plasmaWordIndexEntity[] indexEntities = selectTransferIndexes(startPointHash, indexCount);
        if ((indexEntities == null) || (indexEntities.length == 0)) {
            log.logDebug("No Index available for Index Transfer, hash start-point " + startPointHash);
            return -1;
        }
        // count the indexes again, can be smaller as expected
        indexCount = 0; for (int i = 0; i < indexEntities.length; i++) indexCount += indexEntities[i].size();
        
        // find start point for DHT-selection
        String keyhash = indexEntities[indexEntities.length - 1].wordHash();
        
        // iterate over DHT-peers and send away the indexes
        yacySeed seed;
        int hc = 0;
        Enumeration e = yacyCore.dhtAgent.getAcceptRemoteIndexSeeds(keyhash);
        String error;
        String peerNames = "";
        while ((e.hasMoreElements()) && (hc < peerCount)) {
            seed = (yacySeed) e.nextElement();
            if (seed != null) {
                error = yacyClient.transferIndex(seed, indexEntities, urlPool.loadedURL);
                if (error == null) {
                    log.logInfo("Index Transfer of " + indexCount + " words [" + indexEntities[0].wordHash() + " .. " + indexEntities[indexEntities.length-1].wordHash() + "] to peer " + seed.getName() + ":" + seed.hash + " successfull");
                    peerNames += ", " + seed.getName();
                    hc++;
                } else {
                    log.logWarning("Index Transfer to peer " + seed.getName() + ":" + seed.hash + " failed:'" + error + "', disconnecting peer");
                    yacyCore.peerActions.peerDeparture(seed);
                }
            }
        }
        if (peerNames.length() > 0) peerNames = peerNames.substring(2); // remove comma
        
        // clean up and finish with deletion of indexes
        if (hc >= peerCount) {
            // success
            if (delete) {
                try {
                    if (deleteTransferIndexes(indexEntities)) {
                        log.logDebug("Deleted all transferred whole-word indexes locally");
                        return indexCount;
                    } else {
                        log.logError("Deleted not all transferred whole-word indexes");
                        return -1;
                    }
                } catch (IOException ee) {
                    log.logError("Deletion of Indexes not possible:" + ee.getMessage());
                    ee.printStackTrace();
                    return -1;
                }
            } else {
		// simply close the indexEntities
		for (int i = 0; i < indexEntities.length; i++) try {
		    indexEntities[i].close();
		} catch (IOException ee) {}
	    }
            return indexCount;
        } else {
            log.logError("Index distribution failed. Too less peers (" + hc + ") received the index, not deleted locally.");
            return -1;
        }
    }

    private plasmaWordIndexEntity[] selectTransferIndexes(String hash, int count) {
        Vector tmpEntities = new Vector();
        String nexthash = "";
        try {
            Iterator wordHashIterator = wordIndex.wordHashes(hash, true, true);
            plasmaWordIndexEntity indexEntity, tmpEntity;
            Enumeration urlEnum;
            plasmaWordIndexEntry indexEntry;
            while ((count > 0) && (wordHashIterator.hasNext()) &&
                   ((nexthash = (String) wordHashIterator.next()) != null) && (nexthash.trim().length() > 0)) {
                indexEntity = wordIndex.getEntity(nexthash, true);
                if (indexEntity.size() == 0) {
                    indexEntity.deleteComplete();
                } else if (indexEntity.size() <= count) {
                    // take the whole entity
                    tmpEntities.add(indexEntity);
                    log.logDebug("Selected Whole Index (" + indexEntity.size() + " urls) for word " + indexEntity.wordHash());
                    count -= indexEntity.size();
                } else {
                    // make an on-the-fly entity and insert values
                    tmpEntity = new plasmaWordIndexEntity(indexEntity.wordHash());
                    urlEnum = indexEntity.elements(true);
                    while ((urlEnum.hasMoreElements()) && (count > 0)) {
                        indexEntry = (plasmaWordIndexEntry) urlEnum.nextElement();
                        tmpEntity.addEntry(indexEntry);
                        count--;
                    }
                    urlEnum = null;
                    log.logDebug("Selected Partial Index (" + tmpEntity.size() + " from " + indexEntity.size() +" urls) for word " + tmpEntity.wordHash());
                    tmpEntities.add(tmpEntity);
                    indexEntity.close(); // important: is not closed elswhere and cannot be deleted afterwards
                    indexEntity = null;
                }
                
            }
            // transfer to array
            plasmaWordIndexEntity[] indexEntities = new plasmaWordIndexEntity[tmpEntities.size()];
            for (int i = 0; i < tmpEntities.size(); i++) indexEntities[i] = (plasmaWordIndexEntity) tmpEntities.elementAt(i);
            return indexEntities;
        } catch (IOException e) {
            log.logError("selectTransferIndexes IO-Error (hash=" + nexthash + "): " + e.getMessage());
            e.printStackTrace();
            return new plasmaWordIndexEntity[0];
        } catch (kelondroException e) {
            log.logError("selectTransferIndexes database corrupted: " + e.getMessage());
            e.printStackTrace();
            return new plasmaWordIndexEntity[0];
        }
    }
    
    private boolean deleteTransferIndexes(plasmaWordIndexEntity[] indexEntities) throws IOException {
        String wordhash;
        Enumeration urlEnum;
        plasmaWordIndexEntry indexEntry;
        plasmaWordIndexEntity indexEntity;
        String[] urlHashes;
        int sz;
        boolean success = true;
        for (int i = 0; i < indexEntities.length; i++) {
            if (indexEntities[i].isTMPEntity()) {
                // delete entries separately
                int c = 0;
                urlHashes = new String[indexEntities[i].size()];
                urlEnum = indexEntities[i].elements(true);
                while (urlEnum.hasMoreElements()) {
                    indexEntry = (plasmaWordIndexEntry) urlEnum.nextElement();
                    urlHashes[c++] = indexEntry.getUrlHash();
                }
                wordIndex.removeEntries(indexEntities[i].wordHash(), urlHashes, true);
                indexEntity = wordIndex.getEntity(indexEntities[i].wordHash(), true);
                sz = indexEntity.size();
                indexEntity.close();
                log.logDebug("Deleted Partinal Index (" + c + " urls) for word " + indexEntities[i].wordHash() + "; " + sz + " entries left");
                // DEBUG: now try to delete the remaining index. If this works, this routine is fine
                /*
                if (wordIndex.getEntity(indexEntities[i].wordHash()).deleteComplete())
                    System.out.println("DEBUG: trial delete of partial word index " + indexEntities[i].wordHash() + " SUCCESSFULL");
                else 
                    System.out.println("DEBUG: trial delete of partial word index " + indexEntities[i].wordHash() + " FAILED");
                 */
                // end debug
                indexEntities[i].close();
            } else {
                // delete complete file
                if (indexEntities[i].deleteComplete()) {
                    indexEntities[i].close();
		} else {
                    indexEntities[i].close();
                    // have another try...
                    if (!(plasmaWordIndexEntity.wordHash2path(plasmaPath, indexEntities[i].wordHash()).delete())) {
                        success = false;
                        log.logError("Could not delete whole Index for word " + indexEntities[i].wordHash());
                    }
                }
            }
	    indexEntities[i] = null;
        }
        return success;
    }
    
    public int adminAuthenticated(httpHeader header) {
        String adminAccountBase64MD5 = getConfig("adminAccountBase64MD5", "");
        if (adminAccountBase64MD5.length() == 0) return 2; // not necessary
        String authorization = ((String) header.get(httpHeader.AUTHORIZATION, "xxxxxx")).trim().substring(6);
        if (authorization.length() == 0) return 1; // no authentication information given
        if ((((String) header.get("CLIENTIP", "")).equals("localhost")) && (adminAccountBase64MD5.equals(authorization))) return 3; // soft-authenticated for localhost
        if (adminAccountBase64MD5.equals(serverCodings.standardCoder.encodeMD5Hex(authorization))) return 4; // hard-authenticated, all ok
        return 0; // wrong password
    }
    
    public void terminate() {
        this.terminate = true;
        this.shutdownSync.V();
    }
    
    public boolean isTerminated() {
        return this.terminate;
    }
    
    public boolean waitForShutdown() throws InterruptedException {
        this.shutdownSync.P();
        return this.terminate;
    }
}
