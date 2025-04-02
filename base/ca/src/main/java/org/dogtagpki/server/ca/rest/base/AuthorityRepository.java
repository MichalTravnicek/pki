//
// Copyright Red Hat, Inc.
//
// SPDX-License-Identifier: GPL-2.0-or-later
//
package org.dogtagpki.server.ca.rest.base;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.cert.CertificateEncodingException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.dogtagpki.server.authentication.AuthToken;
import org.dogtagpki.server.ca.AuthorityRecord;
import org.dogtagpki.server.ca.CAEngine;
import org.mozilla.jss.netscape.security.util.Utils;
import org.mozilla.jss.netscape.security.x509.X500Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netscape.ca.CertificateAuthority;
import com.netscape.certsrv.authority.AuthorityData;
import com.netscape.certsrv.authority.AuthorityResource;
import com.netscape.certsrv.base.BadRequestDataException;
import com.netscape.certsrv.base.BadRequestException;
import com.netscape.certsrv.base.ConflictingOperationException;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.ForbiddenException;
import com.netscape.certsrv.base.PKIException;
import com.netscape.certsrv.base.ResourceNotFoundException;
import com.netscape.certsrv.base.ServiceUnavailableException;
import com.netscape.certsrv.base.SessionContext;
import com.netscape.certsrv.ca.AuthorityID;
import com.netscape.certsrv.ca.CADisabledException;
import com.netscape.certsrv.ca.CAEnabledException;
import com.netscape.certsrv.ca.CAMissingCertException;
import com.netscape.certsrv.ca.CAMissingKeyException;
import com.netscape.certsrv.ca.CANotFoundException;
import com.netscape.certsrv.ca.CANotLeafException;
import com.netscape.certsrv.ca.CATypeException;
import com.netscape.certsrv.ca.IssuerUnavailableException;
import com.netscape.certsrv.common.OpDef;
import com.netscape.certsrv.common.ScopeDef;
import com.netscape.certsrv.dbs.DBException;
import com.netscape.certsrv.dbs.certdb.CertId;
import com.netscape.certsrv.logging.AuditEvent;
import com.netscape.certsrv.logging.ILogger;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.ldapconn.LdapBoundConnFactory;
import com.netscape.cmscore.logging.Auditor;

import netscape.ldap.LDAPAttribute;
import netscape.ldap.LDAPAttributeSet;
import netscape.ldap.LDAPConnection;
import netscape.ldap.LDAPControl;
import netscape.ldap.LDAPEntry;
import netscape.ldap.LDAPException;

/**
 * @author Marco Fargetta {@literal <mfargett@redhat.com>}
 * @author ftweedal
 */
public class AuthorityRepository {

    private static Logger logger = LoggerFactory.getLogger(AuthorityRepository.class);

    private CAEngine engine;

    public AuthorityRepository(CAEngine engine) {
        this.engine = engine;
    }

    public AuthorityRecord getAuthorityRecord(LDAPEntry entry) throws Exception {

        logger.info("AuthorityRepository: Loading " + entry.getDN());

        AuthorityRecord record = new AuthorityRecord();

        LDAPAttribute authorityIDAttr = entry.getAttribute("authorityID");
        if (authorityIDAttr == null) {
            throw new Exception("Missing authorityID attribute: " + entry.getDN());
        }

        AuthorityID authorityID = new AuthorityID(authorityIDAttr.getStringValues().nextElement());
        record.setAuthorityID(authorityID);

        LDAPAttribute authorityDNAttr = entry.getAttribute("authorityDN");
        if (authorityDNAttr == null) {
            throw new Exception("Missing authorityDN attribute: " + entry.getDN());
        }

        X500Name authorityDN = new X500Name(authorityDNAttr.getStringValues().nextElement());
        record.setAuthorityDN(authorityDN);

        LDAPAttribute parentIDAttr = entry.getAttribute("authorityParentID");
        if (parentIDAttr != null) {
            AuthorityID parentID = new AuthorityID(parentIDAttr.getStringValues().nextElement());
            record.setParentID(parentID);
        }

        LDAPAttribute parentDNAttr = entry.getAttribute("authorityParentDN");
        if (parentDNAttr != null) {
            X500Name parentDN = new X500Name(parentDNAttr.getStringValues().nextElement());
            record.setParentDN(parentDN);
        }

        LDAPAttribute descriptionAttr = entry.getAttribute("description");
        if (descriptionAttr != null) {
            String description = descriptionAttr.getStringValues().nextElement();
            record.setDescription(description);
        }

        LDAPAttribute enabledAttr = entry.getAttribute("authorityEnabled");
        if (enabledAttr != null) {
            String enabledString = enabledAttr.getStringValues().nextElement();
            record.setEnabled(enabledString.equalsIgnoreCase("TRUE"));
        }

        LDAPAttribute serialAttr = entry.getAttribute("authoritySerial");
        if (serialAttr != null) {
            CertId certID = new CertId(new BigInteger(serialAttr.getStringValueArray()[0]));
            record.setSerialNumber(certID);
        }

        LDAPAttribute keyNicknameAttr = entry.getAttribute("authorityKeyNickname");
        if (keyNicknameAttr == null) {
            throw new Exception("Missing authorityKeyNickname attribute: " + entry.getDN());
        }

        String keyNickname = keyNicknameAttr.getStringValues().nextElement();
        record.setKeyNickname(keyNickname);

        Collection<String> keyHosts;
        LDAPAttribute keyHostAttr = entry.getAttribute("authorityKeyHost");
        if (keyHostAttr == null) {
            keyHosts = Collections.emptyList();
        } else {
            Enumeration<String> keyHostsEnum = keyHostAttr.getStringValues();
            keyHosts = Collections.list(keyHostsEnum);
        }
        record.setKeyHosts(keyHosts);

        String nsUniqueID = entry.getAttribute("nsUniqueId").getStringValueArray()[0];
        record.setNSUniqueID(nsUniqueID);

        LDAPAttribute entryUSNAttr = entry.getAttribute("entryUSN");
        if (entryUSNAttr != null) {
            BigInteger entryUSN = new BigInteger(entryUSNAttr.getStringValueArray()[0]);
            record.setEntryUSN(entryUSN);
        }

        return record;
    }

    public void addAuthorityRecord(AuthorityRecord record) throws Exception {

        AuthorityID authorityID = record.getAuthorityID();
        String aidStr = authorityID.toString();
        String dn = "cn=" + aidStr + "," + engine.getAuthorityBaseDN();
        logger.info("AuthorityRepository: Creating " + dn);

        LDAPAttributeSet attrSet = new LDAPAttributeSet();
        attrSet.add(new LDAPAttribute("objectclass", "authority"));

        logger.info("AuthorityRepository: - authority ID: " + aidStr);
        attrSet.add(new LDAPAttribute("cn", aidStr));
        attrSet.add(new LDAPAttribute("authorityID", aidStr));

        X500Name authorityDN = record.getAuthorityDN();
        logger.info("AuthorityRepository: - authority DN: " + authorityDN);
        attrSet.add(new LDAPAttribute("authorityDN", authorityDN.toLdapDNString()));

        AuthorityID parentID = record.getParentID();
        if (parentID != null) {
            logger.info("AuthorityRepository: - parent ID: " + parentID);
            attrSet.add(new LDAPAttribute("authorityParentID", parentID.toString()));
        }

        X500Name parentDN = record.getParentDN();
        if (parentDN != null) {
            logger.info("AuthorityRepository: - parent DN: " + parentDN);
            attrSet.add(new LDAPAttribute("authorityParentDN", parentDN.toLdapDNString()));
        }

        String description = record.getDescription();
        if (description != null) {
            logger.info("AuthorityRepository: - description: " + description);
            attrSet.add(new LDAPAttribute("description", description));
        }

        Boolean enabled = record.getEnabled();
        if (enabled != null) {
            logger.info("AuthorityRepository: - enabled: " + description);
            attrSet.add(new LDAPAttribute("authorityEnabled", enabled ? "TRUE" : "FALSE"));
        }

        String keyNickname = record.getKeyNickname();
        if (keyNickname != null) {
            logger.info("AuthorityRepository: - key nickname: " + keyNickname);
            attrSet.add(new LDAPAttribute("authorityKeyNickname", keyNickname));
        }

        Collection<String> keyHosts = record.getKeyHosts();
        if (!keyHosts.isEmpty()) {
            logger.info("AuthorityRepository: - key hosts: " + keyHosts);
            String[] values = keyHosts.toArray(new String[keyHosts.size()]);
            attrSet.add(new LDAPAttribute("authorityKeyHost", values));
        }

        LDAPEntry entry = new LDAPEntry(dn, attrSet);

        LdapBoundConnFactory connectionFactory = engine.getConnectionFactory();
        LDAPConnection conn = connectionFactory.getConn();
        LDAPControl[] responseControls;

        try {
            conn.add(entry, engine.getUpdateConstraints());
            responseControls = conn.getResponseControls();

        } catch (LDAPException e) {
            throw new DBException("Unable to add authority: " + e.getMessage(), e);

        } finally {
            connectionFactory.returnConn(conn);
        }
    }

    public List<AuthorityData> findCAs(final String id, final String parentID, final String dn, final String issuerDN) throws IOException {
        final X500Name x500dn = dn == null ? null : new X500Name(dn);
        final X500Name x500issuerDN = issuerDN == null ? null : new X500Name(issuerDN);
        logger.info("AuthorityRepository: Getting authorities:");

        return engine.getCAs().stream().
                map(this::readAuthorityData).
                filter(auth -> {
                    if (id != null && !id.equalsIgnoreCase(auth.getID())) return false;
                    if (parentID != null && !parentID.equalsIgnoreCase(auth.getParentID())) return false;
                    try {
                        if (x500dn != null && !x500dn.equals(new X500Name(auth.getDN()))) return false;
                        if (x500issuerDN != null && !x500issuerDN.equals(new X500Name(auth.getIssuerDN()))) return false;
                    } catch (IOException e) {
                        logger.error("AuthorityRepository: Unable to convert DNs for authority {}", auth.getID());
                        return false;
                    }
                    logger.info("AuthorityRepository: - ID: {}", auth.getID());
                    logger.info("AuthorityRepository:   DN: {}", auth.getDN());
                    if (auth.getParentID() != null) {
                        logger.info("AuthorityRepository:   Parent ID: {}", auth.getParentID());
                    }
                    logger.info("AuthorityRepository:   Issuer DN: {}", auth.getIssuerDN());
                    return true;
                }).
                collect(Collectors.toList());
    }

    public AuthorityData getCA(String authId) {
        logger.info("AuthorityRepository: Getting authority {}:", authId);

        AuthorityID aid = null;
        if (!AuthorityResource.HOST_AUTHORITY.equals(authId)) {
            try {
                aid = new AuthorityID(authId);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Bad AuthorityID: " + authId);
            }

        }
        CertificateAuthority ca = engine.getCA(aid);

        if (ca == null)
            throw new ResourceNotFoundException("CA \"" + authId + "\" not found");

        AuthorityData authority = readAuthorityData(ca);

        logger.info("AuthorityRepository:   DN: {}", authority.getDN());
        if (authority.getParentID() != null) {
            logger.info("AuthorityRepository:   Parent ID: {}", authority.getParentID());
        }
        logger.info("AuthorityRepository:   Issuer DN: {}", authority.getIssuerDN());

        return authority;
    }


    public byte[] getBinaryCert(String authId) {

        logger.info("AuthorityRepository: Getting cert for authority {}", authId);

        AuthorityID aid = null;
        if (!AuthorityResource.HOST_AUTHORITY.equals(authId)) {
            try {
                aid = new AuthorityID(authId);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Bad AuthorityID: " + authId);
            }
        }
        CertificateAuthority ca = engine.getCA(aid);

        if (ca == null)
            throw new ResourceNotFoundException("CA \"" + authId + "\" not found");

        org.mozilla.jss.crypto.X509Certificate cert = ca.getCaX509Cert();
        if (cert == null)
            throw new ResourceNotFoundException(
                "Certificate for CA \"" + authId + "\" not available");

        try {
            return cert.getEncoded();
        } catch (CertificateEncodingException e) {
            // this really is a 500 Internal Server Error
            throw new PKIException("Error encoding certificate: " + e);
        }
    }

    public String getPemCert(String authId) {
        byte[] der = getBinaryCert(authId);
        return toPem("CERTIFICATE", der);
    }

    public byte[] getBinaryChain(String authId) {

        logger.info("AuthorityRepository: Getting cert chain for authority {}", authId);

        AuthorityID aid = null;
        if (!AuthorityResource.HOST_AUTHORITY.equals(authId)) {
            try {
                aid = new AuthorityID(authId);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Bad AuthorityID: " + authId);
            }
        }
        CertificateAuthority ca = engine.getCA(aid);

        if (ca == null)
            throw new ResourceNotFoundException("CA \"" + authId + "\" not found");

        org.mozilla.jss.netscape.security.x509.CertificateChain chain = ca.getCACertChain();
        if (chain == null)
            throw new ResourceNotFoundException(
                "Certificate chain for CA \"" + authId + "\" not available");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            chain.encode(out);
        } catch (IOException e) {
            throw new PKIException("Error encoding certificate chain: " + e);
        }
        return out.toByteArray();
    }

    public String getPemChain(String authId) {
        byte[] der = getBinaryChain(authId);
        return toPem("PKCS7", der);
    }

    public AuthorityData createCA(AuthorityData data) {
        logger.info("AuthorityRepository: Creating authority {}", data.getDN());

        CertificateAuthority hostCA = engine.getCA();
        String parentAIDString = data.getParentID();
        AuthorityID parentAID = null;
        if (AuthorityResource.HOST_AUTHORITY.equals(parentAIDString)) {
            parentAID = hostCA.getAuthorityID();
        } else {
            try {
                parentAID = new AuthorityID(parentAIDString);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Bad Authority ID: " + parentAIDString, e);
            }
        }

        Map<String, String> auditParams = new LinkedHashMap<>();
        auditParams.put("dn", data.getDN());
        if (parentAID != null)
            auditParams.put("parent", parentAIDString);
        if (data.getDescription() != null)
            auditParams.put("description", data.getDescription());

        AuthToken authToken = (AuthToken) SessionContext.getContext().get(SessionContext.AUTH_TOKEN);
        try {
            CertificateAuthority subCA = engine.createCA(
                    parentAID,
                    authToken,
                    data.getDN(),
                    data.getDescription());
            audit(ILogger.SUCCESS, OpDef.OP_ADD,
                    subCA.getAuthorityID().toString(), auditParams);
            return readAuthorityData(subCA);
        } catch (IllegalArgumentException | BadRequestDataException e) {
            throw new BadRequestException(e.toString());
        } catch (CANotFoundException e) {
            throw new ResourceNotFoundException(e.toString());
        } catch (IssuerUnavailableException | CADisabledException e) {
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_ADD, "<unknown>", auditParams);
            throw new ConflictingOperationException(e.toString());
        } catch (CAMissingCertException | CAMissingKeyException e) {
            logger.error(CMS.getLogMessage("CMSCORE_CA_SIGNING_CERT_NOT_FOUND", e.toString()), e);
            throw new ServiceUnavailableException(e.toString());
        } catch (Exception e) {
            String message = "Error creating CA: " + e.getMessage();
            logger.error(message, e);
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_ADD, "<unknown>", auditParams);
            throw new PKIException(message, e);
        }
    }

    public AuthorityData modifyCA(String authId, AuthorityData data) {
        logger.info("AuthorityRepository: Modifying authority {}", authId);

        AuthorityID aid = null;
        try {
            aid = new AuthorityID(authId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Bad AuthorityID: " + authId);
        }
        CertificateAuthority ca = engine.getCA(aid);

        if (ca == null)
            throw new ResourceNotFoundException("CA \"" + authId + "\" not found");

        Map<String, String> auditParams = new LinkedHashMap<>();
        if (Boolean.valueOf(ca.getAuthorityEnabled()).equals(data.getEnabled())) {
            logger.info("AuthorityRepository:   enabled: {}", data.getEnabled());
            auditParams.put("enabled", data.getEnabled().toString());
        }

        String curDesc = ca.getAuthorityDescription();
        String newDesc = data.getDescription();
        if (curDesc != null && !curDesc.equals(newDesc)
                || curDesc == null && newDesc != null) {
            logger.info("AuthorityRepository:   description: {}", data.getDescription());
            auditParams.put("description", data.getDescription());
        }

        try {
            engine.modifyAuthority(ca, data.getEnabled(), data.getDescription());
            audit(ILogger.SUCCESS, OpDef.OP_MODIFY, ca.getAuthorityID().toString(), auditParams);
            return readAuthorityData(ca);
        } catch (CATypeException e) {
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_MODIFY, ca.getAuthorityID().toString(), auditParams);
            throw new ForbiddenException(e.toString());
        } catch (IssuerUnavailableException e) {
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_MODIFY, ca.getAuthorityID().toString(), auditParams);
            throw new ConflictingOperationException(e.toString());
        } catch (EBaseException e) {
            String message = "Error modifying authority: " + e.getMessage();
            logger.error(message, e);
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_MODIFY, ca.getAuthorityID().toString(), auditParams);
            throw new PKIException(message, e);
        }
    }

    public void renewCA(String authId, HttpServletRequest request) {
        logger.info("AuthorityRepository: Renewing cert for authority {}", authId);

        AuthorityID aid = null;
        try {
            aid = new AuthorityID(authId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Bad AuthorityID: " + authId);
        }
        CertificateAuthority ca = engine.getCA(aid);

        if (ca == null)
            throw new ResourceNotFoundException("CA \"" + authId + "\" not found");

        Map<String, String> auditParams = new LinkedHashMap<>();

        try {
            engine.renewAuthority(request, ca);
            audit(ILogger.SUCCESS, OpDef.OP_MODIFY, authId, null);
        } catch (CADisabledException e) {
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_MODIFY, authId, auditParams);
            throw new ConflictingOperationException(e.toString());
        } catch (Exception e) {
            String message = "Error renewing authority: " + e.getMessage();
            logger.error(message, e);
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_MODIFY, authId, auditParams);
            throw new PKIException(message, e);
        }
    }

    public void deleteCA(String authId, HttpServletRequest httpReq) {

        logger.info("AuthorityRepository: Deleting authority {}", authId);

        AuthorityID aid = null;
        try {
            aid = new AuthorityID(authId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Bad AuthorityID: " + authId);
        }

        CertificateAuthority ca = engine.getCA(aid);

        if (ca == null)
            throw new ResourceNotFoundException("CA \"" + authId + "\" not found");

        Map<String, String> auditParams = new LinkedHashMap<>();

        try {
            engine.deleteAuthority(httpReq, ca);
            audit(ILogger.SUCCESS, OpDef.OP_DELETE, authId, null);
        } catch (CATypeException e) {
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_DELETE, authId, auditParams);
            throw new ForbiddenException(e.toString());
        } catch (CAEnabledException | CANotLeafException e) {
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_DELETE, authId, auditParams);
            throw new ConflictingOperationException(e.toString());
        } catch (EBaseException e) {
            String message = "Error modifying authority: " + e.getMessage();
            logger.error(message, e);
            auditParams.put("exception", e.toString());
            audit(ILogger.FAILURE, OpDef.OP_DELETE, authId, auditParams);
            throw new PKIException(message, e);
        }
    }

    private AuthorityData readAuthorityData(CertificateAuthority ca)
            throws PKIException {
        String dn;
        try {
            dn = ca.getX500Name().toLdapDNString();
        } catch (IOException e) {
            throw new PKIException("Error reading CA data: could not determine subject DN");
        }

        String issuerDN;
        BigInteger serial;
        try {
            issuerDN = ca.getCACert().getIssuerName().toString();
            serial = ca.getCACert().getSerialNumber();
        } catch (EBaseException e) {
            throw new PKIException("Error reading CA data: missing CA cert", e);
        }

        AuthorityID parentAID = ca.getAuthorityParentID();
        return new AuthorityData(
            ca.isHostAuthority(),
            dn,
            ca.getAuthorityID().toString(),
            parentAID != null ? parentAID.toString() : null,
            issuerDN,
            serial,
            ca.getAuthorityEnabled(),
            ca.getAuthorityDescription(),
            ca.isReady()
        );
    }

    private String toPem(String name, byte[] data) {
        return "-----BEGIN " + name + "-----\n" +
                Utils.base64encode(data, true) +
                "-----END " + name + "-----\n";
    }

    private void audit(
            String status, String op, String id,
            Map<String, String> params) {

        Auditor auditor = engine.getAuditor();
        String msg = CMS.getLogMessage(
                AuditEvent.AUTHORITY_CONFIG,
                auditor.getSubjectID(),
                status,
                auditor.getParamString(ScopeDef.SC_AUTHORITY, op, id, params));
        auditor.log(msg);
    }
}
