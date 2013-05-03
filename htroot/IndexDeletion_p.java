/**
 *  IndexDeletion_p
 *  Copyright 2013 by Michael Peter Christen
 *  First released 29.04.2013 at http://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrDocument;

import net.yacy.cora.date.ISO8601Formatter;
import net.yacy.cora.federate.solr.connector.AbstractSolrConnector;
import net.yacy.cora.federate.solr.connector.SolrConnector;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.data.WorkTables;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.search.Switchboard;
import net.yacy.search.query.QueryModifier;
import net.yacy.search.schema.CollectionSchema;
import net.yacy.search.schema.WebgraphSchema;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class IndexDeletion_p {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        // return variable that accumulates replacements
        final Switchboard sb = (Switchboard) env;
        final serverObjects prop = new serverObjects();

        SolrConnector defaultConnector = sb.index.fulltext().getDefaultConnector();
        SolrConnector webgraphConnector = sb.index.fulltext().getWebgraphConnector();
        defaultConnector.commit(false); // we must do a commit here because the user cannot see a proper count.
        prop.put("doccount", defaultConnector.getSize());
        try {
            prop.put("collectionlist", defaultConnector.getFacets("*:*", 1000, CollectionSchema.collection_sxt.getSolrFieldName()).get(CollectionSchema.collection_sxt.getSolrFieldName()).toString());
        } catch (IOException e1) {
            prop.put("collectionlist", "[]");
        }
        
        // Delete by URL Matching
        String urldelete = post == null ? "" : post.get("urldelete", "");
        boolean urldelete_mm_subpath_checked = post == null ? true : post.get("urldelete-mm", "subpath").equals("subpath");
        prop.put("urldelete", urldelete);
        prop.put("urldelete-mm-subpath-checked", urldelete_mm_subpath_checked ? 1 : 0);
        prop.put("urldelete-mm-regexp-checked", urldelete_mm_subpath_checked ? 0 : 1);
        prop.put("urldelete-active", 0);
        
        // Delete by Age
        int timedelete_number = post == null ? 14 : post.getInt("timedelete-number", 14);
        String timedelete_unit = post == null ? "day" : post.get("timedelete-unit", "day");
        boolean timedelete_source_loaddate_checked = post == null ? true : post.get("timedelete-source", "loaddate").equals("loaddate");
        for (int i = 1; i <= 90; i++) prop.put("timedelete-n-" + i, 0);
        prop.put("timedelete-n-" + timedelete_number, timedelete_number);
        prop.put("timedelete-u-year", timedelete_unit.equals("year") ? 1 : 0);
        prop.put("timedelete-u-month", timedelete_unit.equals("month") ? 1 : 0);
        prop.put("timedelete-u-day", timedelete_unit.equals("day") ? 1 : 0);
        prop.put("timedelete-u-hour", timedelete_unit.equals("hour") ? 1 : 0);
        prop.put("timedelete-source-loaddate-checked", timedelete_source_loaddate_checked ? 1 : 0);
        prop.put("timedelete-source-lastmodified-checked", timedelete_source_loaddate_checked ? 0 : 1);
        prop.put("timedelete-active", 0);
        
        // Delete Collections
        boolean collectiondelete_mode_unassigned_checked = post == null ? true : post.get("collectiondelete-mode", "unassigned").equals("unassigned");
        String collectiondelete = post == null ? "" : post.get("collectiondelete", "");
        prop.put("collectiondelete-mode-unassigned-checked", collectiondelete_mode_unassigned_checked ? 1 : 0);
        prop.put("collectiondelete-mode-assigned-checked", collectiondelete_mode_unassigned_checked ? 0 : 1);
        prop.put("collectiondelete", collectiondelete);
        prop.put("collectiondelete-active", 0);
        
        // Delete by Solr Query
        prop.put("querydelete", "");
        String querydelete = post == null ? "" : post.get("querydelete", "");
        prop.put("querydelete", querydelete);
        prop.put("querydelete-active", 0);
        
        int count = post == null ? -1 : post.getInt("count", -1);

        if (post != null && (post.containsKey("simulate-urldelete") || post.containsKey("engage-urldelete"))) {
            boolean simulate = post.containsKey("simulate-urldelete");
            // parse the input
            urldelete = urldelete.trim(); 
            String[] stubURLs = urldelete.indexOf('\n') > 0 || urldelete.indexOf('\r') > 0 ? urldelete.split("[\\r\\n]+") : urldelete.split(Pattern.quote("|"));
            Set<String> ids = new HashSet<String>();
            for (String urlStub: stubURLs) {
                if (urlStub == null || urlStub.length() == 0) continue;
                int pos = urlStub.indexOf("://",0);
                if (pos == -1) {
                    if (urlStub.startsWith("www")) urlStub = "http://" + urlStub;
                    if (urlStub.startsWith("ftp")) urlStub = "ftp://" + urlStub;
                }
                try {
                    DigestURI u = new DigestURI(urlStub);
                    BlockingQueue<SolrDocument> dq = defaultConnector.concurrentDocumentsByQuery(CollectionSchema.host_s.getSolrFieldName() + ":" + u.getHost(), 0, 100000000, Long.MAX_VALUE, 100, CollectionSchema.id.getSolrFieldName(), CollectionSchema.sku.getSolrFieldName());
                    SolrDocument doc;
                    try {
                        while ((doc = dq.take()) != AbstractSolrConnector.POISON_DOCUMENT) {
                            String url = (String) doc.getFieldValue(CollectionSchema.sku.getSolrFieldName());
                            if (url.startsWith(urlStub)) ids.add((String) doc.getFieldValue(CollectionSchema.id.getSolrFieldName()));
                        }
                    } catch (InterruptedException e) {
                    }
                    sb.tables.recordAPICall(post, "IndexDeletion_p.html", WorkTables.TABLE_API_TYPE_DELETION, "deletion, docs matching with " + urldelete);
                } catch (MalformedURLException e) {}
            }
            
            if (simulate) {
                count = ids.size();
                prop.put("urldelete-active", count == 0 ? 2 : 1);
            } else {
                try {
                    defaultConnector.deleteByIds(ids);
                    //webgraphConnector.deleteByQuery(webgraphQuery);
                } catch (IOException e) {
                }
                prop.put("urldelete-active", 2);
            }
            prop.put("urldelete-active_count", count);
        }

        if (post != null && (post.containsKey("simulate-timedelete") || post.containsKey("engage-timedelete"))) {
            boolean simulate = post.containsKey("simulate-timedelete");
            Date deleteageDate = null;
            long t = timeParser(timedelete_number, timedelete_unit); // year, month, day, hour
            if (t > 0) deleteageDate = new Date(t);
            final String collection1Query = (timedelete_source_loaddate_checked ? CollectionSchema.load_date_dt : CollectionSchema.last_modified).getSolrFieldName() + ":[* TO " + ISO8601Formatter.FORMATTER.format(deleteageDate) + "]";
            final String webgraphQuery = (timedelete_source_loaddate_checked ? WebgraphSchema.load_date_dt : WebgraphSchema.last_modified).getSolrFieldName() + ":[* TO " + ISO8601Formatter.FORMATTER.format(deleteageDate) + "]";
            if (simulate) {
                try {
                    count = (int) defaultConnector.getCountByQuery(collection1Query);
                } catch (IOException e) {
                }
                prop.put("timedelete-active", count == 0 ? 2 : 1);
            } else {
                try {
                    defaultConnector.deleteByQuery(collection1Query);
                    webgraphConnector.deleteByQuery(webgraphQuery);
                    sb.tables.recordAPICall(post, "IndexDeletion_p.html", WorkTables.TABLE_API_TYPE_DELETION, "deletion, docs older than " + timedelete_number + " " + timedelete_unit);
                } catch (IOException e) {
                }
                prop.put("timedelete-active", 2);
            }
            prop.put("timedelete-active_count", count);
        }
        
        if (post != null && (post.containsKey("simulate-collectiondelete") || post.containsKey("engage-collectiondelete"))) {
            boolean simulate = post.containsKey("simulate-collectiondelete");
            collectiondelete = collectiondelete.replaceAll(" ","").replaceAll(",", "|");
            String query = collectiondelete_mode_unassigned_checked ? "-" + CollectionSchema.collection_sxt + ":[* TO *]" : collectiondelete.length() == 0 ? CollectionSchema.collection_sxt + ":\"\"" : QueryModifier.parseCollectionExpression(collectiondelete);
            if (simulate) {
                try {
                    count = (int) defaultConnector.getCountByQuery(query);
                } catch (IOException e) {
                }
                prop.put("collectiondelete-active", count == 0 ? 2 : 1);
            } else {
                try {
                    defaultConnector.deleteByQuery(query);
                    sb.tables.recordAPICall(post, "IndexDeletion_p.html", WorkTables.TABLE_API_TYPE_DELETION, "deletion, collection " + collectiondelete);
                } catch (IOException e) {
                }
                prop.put("collectiondelete-active", 2);
            }
            prop.put("collectiondelete-active_count", count);
        }
        
        if (post != null && (post.containsKey("simulate-querydelete") || post.containsKey("engage-querydelete"))) {
            boolean simulate = post.containsKey("simulate-querydelete");
            if (simulate) {
                try {
                    count = (int) defaultConnector.getCountByQuery(querydelete);
                } catch (IOException e) {
                }
                prop.put("querydelete-active", count == 0 ? 2 : 1);
            } else {
                try {
                    defaultConnector.deleteByQuery(querydelete);
                    sb.tables.recordAPICall(post, "IndexDeletion_p.html", WorkTables.TABLE_API_TYPE_DELETION, "deletion, solr query, q = " + querydelete);
                } catch (IOException e) {
                }
                prop.put("querydelete-active", 2);
            }
            prop.put("querydelete-active_count", count);
        }
        
        // return rewrite properties
        return prop;
    }

    private static long timeParser(final int number, final String unit) {
        if ("year".equals(unit)) return System.currentTimeMillis() - number * 1000L * 60L * 60L * 24L * 365L;
        if ("month".equals(unit)) return System.currentTimeMillis() - number * 1000L * 60L * 60L * 24L * 30L;
        if ("day".equals(unit)) return System.currentTimeMillis() - number * 1000L * 60L * 60L * 24L;
        if ("hour".equals(unit)) return System.currentTimeMillis() - number * 1000L * 60L * 60L;
        if ("minute".equals(unit)) return System.currentTimeMillis() - number * 1000L * 60L;
        return 0L;
    }

}