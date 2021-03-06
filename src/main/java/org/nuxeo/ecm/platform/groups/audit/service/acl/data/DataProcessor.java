/*
 * (C) Copyright 2013 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Martin Pernollet
 */

package org.nuxeo.ecm.platform.groups.audit.service.acl.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.Path;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.platform.groups.audit.service.acl.Pair;
import org.nuxeo.ecm.platform.groups.audit.service.acl.filter.IContentFilter;

import com.google.common.collect.Multimap;

/**
 * Gather various data and statistics about a document tree
 *
 * @author Martin Pernollet <mpernollet@nuxeo.com>
 */
public class DataProcessor implements IDataProcessor {
    protected static Log log = LogFactory.getLog(DataProcessor.class);

    protected int documentMinDepth;

    protected int documentTreeDepth;

    protected SortedSet<String> userAndGroups;

    protected SortedSet<String> permissions;

    protected ProcessorStatus status;

    protected String information;

    protected Collection<DocumentSummary> allDocuments;

    protected IContentFilter filter;

    protected AclSummaryExtractor acl;

    public enum ProcessorStatus {
        SUCCESS, ERROR_TOO_MANY_DOCUMENTS, ERROR_TOO_LONG_PROCESS, ERROR
    }

    protected int n;

    protected TicToc t = new TicToc();

    /* */

    public DataProcessor(IContentFilter filter) {
        this.filter = filter;
        this.acl = new AclSummaryExtractor(filter);
    }

    @Override
    public void analyze(CoreSession session) throws ClientException {
        analyze(session, session.getRootDocument(), 0);
    }

    @Override
    public void analyze(CoreSession session, DocumentModel doc, int timeout) throws ClientException {
        init();
        doAnalyze(session, doc, timeout);
        log();
    }

    public void init() {
        userAndGroups = new TreeSet<String>();
        permissions = new TreeSet<String>();
        documentMinDepth = Integer.MAX_VALUE;
        documentTreeDepth = 0;
    }

    // timeout ignored
    protected void doAnalyze(CoreSession session, DocumentModel root, int timeout) throws ClientException {
        // get data
        final DataFetch fetch = new DataFetch();
        DocumentModelList list;
        try {
            list = fetch.getAllChildren(session, root);
        } catch (IOException e) {
            throw new ClientException(e);
        }
        initSummarySet();

        processDocument(root);

        n = list.size();
        t.tic();
        for (DocumentModel d : list) {
            processDocument(d);
        }
        status = ProcessorStatus.SUCCESS;
    }

    protected void initSummarySet() {
        allDocuments = new ArrayList<DocumentSummary>(1000);
    }

    /**
     * Extract relevant information from document model, to only keep a {@link DocumentSummary} and a few general
     * informations about the document repository.
     */
    protected void processDocument(DocumentModel doc) throws ClientException {
        final DocumentSummary da = computeSummary(doc);
        updateTreeSize(da);
        computeGlobalAclSummary(doc);
        allDocuments.add(da);
        // log.debug(getNumberOfDocuments() + "/" + n + " documents, elapsed " +
        // t.toc() + "s, docdepth:" + da.getDepth());
    }

    /** Extract usefull document information for report rendering */
    protected DocumentSummary computeSummary(DocumentModel doc) throws ClientException {
        String title = doc.getTitle();
        String path = doc.getPathAsString();
        if (path == null)
            path = "";
        int depth = computeDepth(doc);

        boolean lock = acl.hasLockInheritanceACE(doc);
        Multimap<String, Pair<String, Boolean>> aclLo = acl.getAclLocalByUser(doc);
        Multimap<String, Pair<String, Boolean>> aclIn = acl.getAclInheritedByUser(doc);

        DocumentSummary da = new DocumentSummary(title, depth, lock, aclLo, aclIn, path);
        return da;
    }

    protected int computeDepth(DocumentModel m) {
        Path path = m.getPath();
        return path.segmentCount();
    }

    /** report global tree size */
    protected int updateTreeSize(DocumentSummary da) {
        int depth = da.getDepth();
        if (depth > documentTreeDepth)
            documentTreeDepth = depth;
        if (depth < documentMinDepth)
            documentMinDepth = depth;
        return depth;
    }

    /** store set of users and set of permission types */
    protected void computeGlobalAclSummary(DocumentModel doc) throws ClientException {
        Pair<HashSet<String>, HashSet<String>> s = acl.getAclSummary(doc);
        userAndGroups.addAll(s.a);
        permissions.addAll(s.b);
    }

    /* RESULTS */

    /** Ranked so that appear like a tree. */
    @Override
    public Collection<DocumentSummary> getAllDocuments() {
        return allDocuments;
    }

    @Override
    public Set<String> getUserAndGroups() {
        return userAndGroups;
    }

    @Override
    public Set<String> getPermissions() {
        return permissions;
    }

    @Override
    public int getDocumentTreeMaxDepth() {
        return documentTreeDepth;
    }

    @Override
    public int getDocumentTreeMinDepth() {
        return documentMinDepth;
    }

    @Override
    public int getNumberOfDocuments() {
        return allDocuments.size();
    }

    @Override
    public ProcessorStatus getStatus() {
        return status;
    }

    @Override
    public String getInformation() {
        return information;
    }

    /* */

    public void log() {
        log.debug("doc tree depth    : " + getDocumentTreeMaxDepth());
        log.debug("#docs (or folders): "
                + getNumberOfDocuments()
                + " (analyzed by processor, may differ from actual number of doc in repo if exceeding timeout or max number of doc)");
        log.debug("#users (or groups): " + getUserAndGroups().size()
                + " (mentionned in ACLs, may differ from actual user directory)");
        log.debug("#permissions types: " + getPermissions().size() + " (mentionned in ACLs)");
    }
}
