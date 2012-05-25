/* The contents of this file are subject to the license and copyright terms
 * detailed in the license directory at the root of the source tree (also
 * available online at http://fedora-commons.org/license/).
 */
package org.fcrepo.server.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.fcrepo.common.Constants;
import org.fcrepo.server.Context;
import org.fcrepo.server.config.ModuleConfiguration;
import org.fcrepo.server.errors.GeneralException;
import org.fcrepo.server.errors.ModuleInitializationException;
import org.fcrepo.server.errors.authorization.AuthzDeniedException;
import org.fcrepo.server.errors.authorization.AuthzException;
import org.fcrepo.server.errors.authorization.AuthzOperationalException;
import org.fcrepo.server.errors.authorization.AuthzPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.xacml.PDP;
import com.sun.xacml.PDPConfig;
import com.sun.xacml.attr.StringAttribute;
import com.sun.xacml.ctx.Attribute;
import com.sun.xacml.ctx.RequestCtx;
import com.sun.xacml.ctx.ResponseCtx;
import com.sun.xacml.ctx.Result;
import com.sun.xacml.ctx.Subject;

/**
 * @author Bill Niebel
 */
public class PolicyEnforcementPointImpl implements PolicyEnforcementPoint {

    private static final Logger logger =
            LoggerFactory.getLogger(PolicyEnforcementPointImpl.class);

    private static final String ROLE = org.fcrepo.server.security.PolicyEnforcementPoint.class.getName();

    private static PolicyEnforcementPoint singleton = null;

    private static int count = 0;

    private static final String OWNER_ID_SEPARATOR_CONFIG_KEY = "OWNER-ID-SEPARATOR";

    private static final String ENFORCE_MODE_CONFIG_KEY = "ENFORCE-MODE";

    static final String ENFORCE_MODE_ENFORCE_POLICIES = "enforce-policies";

    static final String ENFORCE_MODE_PERMIT_ALL_REQUESTS =
            "permit-all-requests";

    static final String ENFORCE_MODE_DENY_ALL_REQUESTS = "deny-all-requests";

    private final URI XACML_SUBJECT_ID_URI;

    private final URI XACML_ACTION_ID_URI;

    private final URI XACML_RESOURCE_ID_URI;

    private final URI SUBJECT_ID_URI;

    private final URI ACTION_ID_URI;

    private final URI ACTION_API_URI;

    private final URI ACTION_CONTEXT_URI;

    private final URI RESOURCE_ID_URI;

    private final URI RESOURCE_NAMESPACE_URI;

    private final ContextRegistry m_registry;

    private final List<com.sun.xacml.finder.AttributeFinderModule> m_attrFinderModules = new ArrayList<com.sun.xacml.finder.AttributeFinderModule>(0);

    private String ownerIdSeparator = ",";

    private String m_enforceMode = ENFORCE_MODE_ENFORCE_POLICIES;

    private final PDPConfig m_pdpConfig;

    private PDP m_pdp = null;

    public PolicyEnforcementPointImpl(PDPConfig pdpConfig, ContextRegistry registry, ModuleConfiguration authzConfiguration)
            throws ModuleInitializationException {
        m_pdpConfig = pdpConfig;
        m_registry = registry;
        Map<String,String> moduleParameters = authzConfiguration.getParameters();
        if (moduleParameters.containsKey(ENFORCE_MODE_CONFIG_KEY)) {
            m_enforceMode = moduleParameters.get(ENFORCE_MODE_CONFIG_KEY);
            if (ENFORCE_MODE_ENFORCE_POLICIES.equals(m_enforceMode)) {
            } else if (ENFORCE_MODE_PERMIT_ALL_REQUESTS.equals(m_enforceMode)) {
            } else if (ENFORCE_MODE_DENY_ALL_REQUESTS.equals(m_enforceMode)) {
            } else {
                throw new ModuleInitializationException("invalid enforceMode from config \"" + m_enforceMode + "\"", ROLE);
            }
        }

        if (moduleParameters.containsKey(OWNER_ID_SEPARATOR_CONFIG_KEY)) {
            ownerIdSeparator =
                    moduleParameters.get(OWNER_ID_SEPARATOR_CONFIG_KEY);
            logger.debug("ownerIdSeparator is [{}]", ownerIdSeparator);
        }

        URI xacmlSubjectIdUri = null;
        URI xacmlActionIdUri = null;
        URI xacmlResourceIdUri = null;

        URI subjectIdUri = null;
        URI actionIdUri = null;
        URI actionApiUri = null;
        URI contextUri = null;
        URI pidUri = null;
        URI namespaceUri = null;
        try {
            xacmlSubjectIdUri = new URI(XACML_SUBJECT_ID);
            xacmlActionIdUri = new URI(XACML_ACTION_ID);
            xacmlResourceIdUri = new URI(XACML_RESOURCE_ID);
            subjectIdUri = new URI(Constants.SUBJECT.LOGIN_ID.uri);
            actionIdUri = new URI(Constants.ACTION.ID.uri);
            actionApiUri = new URI(Constants.ACTION.API.uri);
            contextUri = new URI(Constants.ACTION.CONTEXT_ID.uri);
            pidUri = new URI(Constants.OBJECT.PID.uri);
            namespaceUri = new URI(Constants.OBJECT.NAMESPACE.uri);
        } catch (URISyntaxException e) {
            logger.error("Bad URI syntax", e);
        } finally {
            XACML_SUBJECT_ID_URI = xacmlSubjectIdUri;
            XACML_ACTION_ID_URI = xacmlActionIdUri;
            XACML_RESOURCE_ID_URI = xacmlResourceIdUri;
            SUBJECT_ID_URI = subjectIdUri;
            ACTION_ID_URI = actionIdUri;
            ACTION_API_URI = actionApiUri;
            ACTION_CONTEXT_URI = contextUri;
            RESOURCE_ID_URI = pidUri;
            RESOURCE_NAMESPACE_URI = namespaceUri;
        }
    }

    public void init() throws GeneralException {
        newPdp();
    }

    /* (non-Javadoc)
     * @see org.fcrepo.server.security.PolicyEnforcementPoint#setAttributeFinderModules(java.util.List)
     */
    @Override
    public void setAttributeFinderModules(List<com.sun.xacml.finder.AttributeFinderModule> attrFinderModules){
        this.m_attrFinderModules.clear();
        this.m_attrFinderModules.addAll(attrFinderModules);
    }

    @Override
    public final void newPdp() throws GeneralException {

        PDP pdp = new PDP(m_pdpConfig);
        synchronized (this) {
            this.m_pdp = pdp;
            //so enforce() will wait, if this pdp update is in progress
        }
    }

    /* (non-Javadoc)
     * @see org.fcrepo.server.security.PolicyEnforcementPoint#inactivate()
     */
    @Override
    public void inactivate() {
        destroy();
    }

    /* (non-Javadoc)
     * @see org.fcrepo.server.security.PolicyEnforcementPoint#destroy()
     */
    @Override
    public void destroy() {
        m_pdp = null;
    }

    private final Set<Subject> wrapSubjects(String subjectLoginId) {
        logger.debug("wrapSubjectIdAsSubjects(): " + subjectLoginId);
        StringAttribute stringAttribute = new StringAttribute("");
        Attribute subjectAttribute =
                new Attribute(XACML_SUBJECT_ID_URI, null, null, stringAttribute);
        logger.debug("wrapSubjectIdAsSubjects(): subjectAttribute, id="
                + subjectAttribute.getId() + ", type="
                + subjectAttribute.getType() + ", value="
                + subjectAttribute.getValue());
        Set<Attribute> subjectAttributes = new HashSet<Attribute>();
        subjectAttributes.add(subjectAttribute);
        if (subjectLoginId != null && !"".equals(subjectLoginId)) {
            stringAttribute = new StringAttribute(subjectLoginId);
            subjectAttribute =
                    new Attribute(SUBJECT_ID_URI, null, null, stringAttribute);
            logger.debug("wrapSubjectIdAsSubjects(): subjectAttribute, id="
                    + subjectAttribute.getId() + ", type="
                    + subjectAttribute.getType() + ", value="
                    + subjectAttribute.getValue());
        }
        subjectAttributes.add(subjectAttribute);
        Subject singleSubject = new Subject(subjectAttributes);
        Set<Subject> subjects = new HashSet<Subject>();
        subjects.add(singleSubject);
        return subjects;
    }

    private final Set<Attribute> wrapActions(String actionId,
                                  String actionApi,
                                  String contextIndex) {
        Set<Attribute> actions = new HashSet<Attribute>();
        Attribute action =
                new Attribute(XACML_ACTION_ID_URI,
                              null,
                              null,
                              new StringAttribute(""));
        actions.add(action);
        action =
                new Attribute(ACTION_ID_URI,
                              null,
                              null,
                              new StringAttribute(actionId));
        actions.add(action);
        action =
                new Attribute(ACTION_API_URI,
                              null,
                              null,
                              new StringAttribute(actionApi));
        actions.add(action);
        action =
                new Attribute(ACTION_CONTEXT_URI,
                              null,
                              null,
                              new StringAttribute(contextIndex));
        actions.add(action);
        return actions;
    }

    private final Set<Attribute> wrapResources(String pid, String namespace)
            throws AuthzOperationalException {
        Set<Attribute> resources = new HashSet<Attribute>();
        Attribute attribute = null;
        attribute =
                new Attribute(XACML_RESOURCE_ID_URI,
                              null,
                              null,
                              new StringAttribute(""));
        resources.add(attribute);
        attribute =
                new Attribute(RESOURCE_ID_URI,
                              null,
                              null,
                              new StringAttribute(pid));
        resources.add(attribute);
        attribute =
                new Attribute(RESOURCE_NAMESPACE_URI,
                              null,
                              null,
                              new StringAttribute(namespace));
        resources.add(attribute);
        return resources;
    }

    private int n = 0;

    private synchronized int next() {
        return n++;
    }

    private final Set NULL_SET = new HashSet();

    /* (non-Javadoc)
     * @see org.fcrepo.server.security.PolicyEnforcementPoint#enforce(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, org.fcrepo.server.Context)
     */
    @Override
    public final void enforce(String subjectId,
                              String action,
                              String api,
                              String pid,
                              String namespace,
                              Context context) throws AuthzException {

        long enforceStartTime = System.currentTimeMillis();
        try {
            synchronized (this) {
                //wait, if pdp update is in progress
            }
            if (ENFORCE_MODE_PERMIT_ALL_REQUESTS.equals(m_enforceMode)) {
                logger.debug("permitting request because enforceMode==ENFORCE_MODE_PERMIT_ALL_REQUESTS");
            } else if (ENFORCE_MODE_DENY_ALL_REQUESTS.equals(m_enforceMode)) {
                logger.debug("denying request because enforceMode==ENFORCE_MODE_DENY_ALL_REQUESTS");
                throw new AuthzDeniedException("all requests are currently denied");
            } else if (!ENFORCE_MODE_ENFORCE_POLICIES.equals(m_enforceMode)) {
                logger.debug("denying request because enforceMode is invalid");
                throw new AuthzOperationalException("invalid enforceMode from config \"" + m_enforceMode + "\"");
            } else {
                ResponseCtx response = null;
                String contextIndex = null;
                try {
                    contextIndex = (new Integer(next())).toString();
                    logger.debug("context index set={}", contextIndex);
                    Set<Subject> subjects = wrapSubjects(subjectId);
                    Set<Attribute> actions = wrapActions(action, api, contextIndex);
                    Set<Attribute> resources = wrapResources(pid, namespace);

                    RequestCtx request =
                            new RequestCtx(subjects,
                                           resources,
                                           actions,
                                           NULL_SET);
                    Iterator<Attribute> tempit = actions.iterator();
                    while (tempit.hasNext()) {
                        Attribute tempobj = tempit.next();
                        logger.debug("request action has {}={}", tempobj.getId(), tempobj.getValue().toString());
                    }
                    m_registry.registerContext(contextIndex, context);
                    long st = System.currentTimeMillis();
                    try {
                        response = m_pdp.evaluate(request);
                    } finally {
                        long dur = System.currentTimeMillis() - st;
                        logger.debug("Policy evaluation took {}ms.", dur);
                    }

                    logger.debug("in pep, after evaluate() called");
                } catch (Throwable t) {
                    logger.error("Error evaluating policy", t);
                    throw new AuthzOperationalException("");
                } finally {
                    m_registry.unregisterContext(contextIndex);
                }
                logger.debug("in pep, before denyBiasedAuthz() called");
                if (!denyBiasedAuthz(response.getResults())) {
                    throw new AuthzDeniedException("");
                }
            }
            if (context.getNoOp()) {
                throw new AuthzPermittedException("noOp");
            }
        } finally {
            long dur = System.currentTimeMillis() - enforceStartTime;
            logger.debug("Policy enforcement took {}ms.", dur);
        }
    }

    private static final boolean denyBiasedAuthz(Set set) {
        int nPermits = 0; //explicit permit returned
        int nDenies = 0; //explicit deny returned
        int nNotApplicables = 0; //no targets matched
        int nIndeterminates = 0; //for targets matched, no rules matched
        int nWrongs = 0; //none of the above, i.e., unreported failure, should not happen
        Iterator it = set.iterator();
        while (it.hasNext()) {
            Result result = (Result) it.next();
            int decision = result.getDecision();
            switch (decision) {
                case Result.DECISION_PERMIT:
                    nPermits++;
                    break;
                case Result.DECISION_DENY:
                    nDenies++;
                    break;
                case Result.DECISION_INDETERMINATE:
                    nIndeterminates++;
                    break;
                case Result.DECISION_NOT_APPLICABLE:
                    nNotApplicables++;
                    break;
                default:
                    nWrongs++;
                    break;
            }
        }
        logger.debug("AUTHZ:  permits=" + nPermits + " denies=" + nDenies
                + " indeterminates=" + nIndeterminates + " notApplicables="
                + nNotApplicables + " unexpecteds=" + nWrongs);
        return nPermits >= 1 && nDenies == 0 && nIndeterminates == 0
                && nWrongs == 0; // don't care about NotApplicables
    }

}