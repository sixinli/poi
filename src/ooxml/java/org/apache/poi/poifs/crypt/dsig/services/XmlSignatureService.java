/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

/* ====================================================================
   This product contains an ASLv2 licensed version of the OOXML signer
   package from the eID Applet project
   http://code.google.com/p/eid-applet/source/browse/trunk/README.txt  
   Copyright (C) 2008-2014 FedICT.
   ================================================================= */ 

package org.apache.poi.poifs.crypt.dsig.services;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.crypto.MarshalException;
import javax.xml.crypto.URIDereferencer;
import javax.xml.crypto.XMLStructure;
import javax.xml.crypto.dom.DOMCryptoContext;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Manifest;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.XMLObject;
import javax.xml.crypto.dsig.XMLSignContext;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactoryConfigurationError;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageNamespaces;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.poifs.crypt.CryptoFunctions;
import org.apache.poi.poifs.crypt.HashAlgorithm;
import org.apache.poi.poifs.crypt.dsig.HorribleProxies.DOMReferenceIf;
import org.apache.poi.poifs.crypt.dsig.HorribleProxies.DOMSignedInfoIf;
import org.apache.poi.poifs.crypt.dsig.HorribleProxies.DOMXMLSignatureIf;
import org.apache.poi.poifs.crypt.dsig.HorribleProxies.XMLSignatureIf;
import org.apache.poi.poifs.crypt.dsig.HorribleProxy;
import org.apache.poi.poifs.crypt.dsig.OOXMLURIDereferencer;
import org.apache.poi.poifs.crypt.dsig.SignatureInfo;
import org.apache.poi.poifs.crypt.dsig.facets.KeyInfoSignatureFacet;
import org.apache.poi.poifs.crypt.dsig.facets.OOXMLSignatureFacet;
import org.apache.poi.poifs.crypt.dsig.facets.Office2010SignatureFacet;
import org.apache.poi.poifs.crypt.dsig.facets.SignatureFacet;
import org.apache.poi.poifs.crypt.dsig.facets.XAdESSignatureFacet;
import org.apache.poi.poifs.crypt.dsig.spi.AddressDTO;
import org.apache.poi.poifs.crypt.dsig.spi.Constants;
import org.apache.poi.poifs.crypt.dsig.spi.DigestInfo;
import org.apache.poi.poifs.crypt.dsig.spi.IdentityDTO;
import org.apache.poi.util.POILogFactory;
import org.apache.poi.util.POILogger;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.w3.x2000.x09.xmldsig.SignatureDocument;
import org.w3.x2000.x09.xmldsig.SignatureType;
import org.w3.x2000.x09.xmldsig.SignatureValueType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;


/**
 * Abstract base class for an XML Signature Service implementation.
 */
public class XmlSignatureService implements SignatureService {
    private static final POILogger LOG = POILogFactory.getLogger(XmlSignatureService.class);

    protected final List<SignatureFacet> signatureFacets;

    private String signatureNamespacePrefix;
    private String signatureId = "idPackageSignature";
    private final HashAlgorithm hashAlgo;
    private final OPCPackage opcPackage;
    private SignatureDocument sigDoc;
    private XAdESSignatureFacet xadesSignatureFacet;
    
    /**
     * Main constructor.
     */
    public XmlSignatureService(HashAlgorithm digestAlgo, OPCPackage opcPackage) {
        this.signatureFacets = new LinkedList<SignatureFacet>();
        this.signatureNamespacePrefix = null;
        this.hashAlgo = digestAlgo;
        this.opcPackage = opcPackage;
        this.sigDoc = null;
    }

    public void initFacets(Date clock) {
        if (clock == null) clock = new Date();
        addSignatureFacet(new OOXMLSignatureFacet(this, clock, hashAlgo));
        addSignatureFacet(new KeyInfoSignatureFacet(true, false, false));

        this.xadesSignatureFacet = new XAdESSignatureFacet(clock, hashAlgo, null);
        this.xadesSignatureFacet.setIdSignedProperties("idSignedProperties");
        this.xadesSignatureFacet.setSignaturePolicyImplied(true);
        /*
         * Work-around for Office 2010.
         */
        this.xadesSignatureFacet.setIssuerNameNoReverseOrder(true);
        addSignatureFacet(this.xadesSignatureFacet);
        addSignatureFacet(new Office2010SignatureFacet());
    }
    
    
    /**
     * Sets the signature Id attribute value used to create the XML signature. A
     * <code>null</code> value will trigger an automatically generated signature
     * Id.
     * 
     * @param signatureId
     */
    protected void setSignatureId(String signatureId) {
            this.signatureId = signatureId;
    }

    /**
     * Sets the XML Signature namespace prefix to be used for signature
     * creation. A <code>null</code> value will omit the prefixing.
     * 
     * @param signatureNamespacePrefix
     */
    protected void setSignatureNamespacePrefix(String signatureNamespacePrefix) {
        this.signatureNamespacePrefix = signatureNamespacePrefix;
    }

    /**
     * Adds a signature facet to this XML signature service.
     * 
     * @param signatureFacet
     */
    public void addSignatureFacet(SignatureFacet... signatureFacets) {
        for (SignatureFacet sf : signatureFacets) {
            this.signatureFacets.add(sf);
        }
    }

    /**
     * Gives back the signature digest algorithm. Allowed values are SHA-1,
     * SHA-256, SHA-384, SHA-512, RIPEND160. The default algorithm is SHA-1.
     * Override this method to select another signature digest algorithm.
     * 
     * @return
     */
    protected HashAlgorithm getSignatureDigestAlgorithm() {
        return null != this.hashAlgo ? this.hashAlgo : HashAlgorithm.sha1;
    }

    /**
     * Override this method to change the URI dereferener used by the signing
     * engine.
     * 
     * @return
     */
    protected URIDereferencer getURIDereferencer() {
        OPCPackage ooxmlDocument = getOfficeOpenXMLDocument();
        return new OOXMLURIDereferencer(ooxmlDocument);
    }

    /**
     * Gives back the human-readable description of what the citizen will be
     * signing. The default value is "XML Document". Override this method to
     * provide the citizen with another description.
     * 
     * @return
     */
    protected String getSignatureDescription() {
        return "Office OpenXML Document";
    }

    /**
     * Gives back the URL of the OOXML to be signed.
     * 
     * @return
     */
    public OPCPackage getOfficeOpenXMLDocument() {
        return opcPackage;
    }
    

    
    /**
     * Gives back the output stream to which to write the signed XML document.
     * 
     * @return
     */
    // protected abstract OutputStream getSignedDocumentOutputStream();

    public DigestInfo preSign(List<DigestInfo> digestInfos,
        List<X509Certificate> signingCertificateChain,
        IdentityDTO identity, AddressDTO address, byte[] photo)
    throws NoSuchAlgorithmException {
        SignatureInfo.initXmlProvider();
    
        LOG.log(POILogger.DEBUG, "preSign");
        HashAlgorithm hashAlgo = getSignatureDigestAlgorithm();

        byte[] digestValue;
        try {
            digestValue = getXmlSignatureDigestValue(hashAlgo, digestInfos, signingCertificateChain);
        } catch (Exception e) {
            throw new RuntimeException("XML signature error: " + e.getMessage(), e);
        }

        String description = getSignatureDescription();
        return new DigestInfo(digestValue, hashAlgo, description);
    }

    public void postSign(byte[] signatureValue, List<X509Certificate> signingCertificateChain)
    throws IOException {
        LOG.log(POILogger.DEBUG, "postSign");
        SignatureInfo.initXmlProvider();

        /*
         * Retrieve the intermediate XML signature document from the temporary  
         * data storage.
         */
        SignatureType sigType = sigDoc.getSignature();

        /*
         * Check ds:Signature node.
         */
        if (!signatureId.equals(sigType.getId())) {
            throw new RuntimeException("ds:Signature not found for @Id: " + signatureId);
        }

        /*
         * Insert signature value into the ds:SignatureValue element
         */
        SignatureValueType sigVal = sigType.getSignatureValue();
        sigVal.setByteArrayValue(signatureValue);

        /*
         * Allow signature facets to inject their own stuff.
         */
        for (SignatureFacet signatureFacet : this.signatureFacets) {
            signatureFacet.postSign(sigType, signingCertificateChain);
        }

        writeDocument();
    }

    @SuppressWarnings("unchecked")
    private byte[] getXmlSignatureDigestValue(HashAlgorithm hashAlgo,
        List<DigestInfo> digestInfos,
        List<X509Certificate> signingCertificateChain)
        throws ParserConfigurationException, NoSuchAlgorithmException,
        InvalidAlgorithmParameterException, MarshalException,
        javax.xml.crypto.dsig.XMLSignatureException,
        TransformerFactoryConfigurationError, TransformerException,
        IOException, SAXException, NoSuchProviderException, XmlException {
        /*
         * DOM Document construction.
         */
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().newDocument();

        /*
         * Signature context construction.
         */
        Key key = new Key() {
            private static final long serialVersionUID = 1L;

            public String getAlgorithm() {
                return null;
            }

            public byte[] getEncoded() {
                return null;
            }

            public String getFormat() {
                return null;
            }
        };
        
        // As of JDK 7, can't use sigDoc here directly, because the
        // setAttributeID will be called and it's not implemented in xmlbeans
        XMLSignContext xmlSignContext = new DOMSignContext(key, doc);
        URIDereferencer uriDereferencer = getURIDereferencer();
        if (null != uriDereferencer) {
            xmlSignContext.setURIDereferencer(uriDereferencer);
        }

        xmlSignContext.putNamespacePrefix(
                "http://schemas.openxmlformats.org/package/2006/digital-signature",
                "mdssi");
        
        if (this.signatureNamespacePrefix != null) {
            /*
             * OOo doesn't like ds namespaces so per default prefixing is off.
             */
            xmlSignContext.putNamespacePrefix(
                javax.xml.crypto.dsig.XMLSignature.XMLNS,
                this.signatureNamespacePrefix);
        }

        XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM", "XMLDSig");

        /*
         * Add ds:References that come from signing client local files.
         */
        List<Reference> references = new LinkedList<Reference>();
        addDigestInfosAsReferences(digestInfos, signatureFactory, references);

        /*
         * Invoke the signature facets.
         */
        String localSignatureId;
        if (null == this.signatureId) {
            localSignatureId = "xmldsig-" + UUID.randomUUID().toString();
        } else {
            localSignatureId = this.signatureId;
        }
        List<XMLObject> objects = new LinkedList<XMLObject>();
        for (SignatureFacet signatureFacet : this.signatureFacets) {
            LOG.log(POILogger.DEBUG, "invoking signature facet: "
                + signatureFacet.getClass().getSimpleName());
            signatureFacet.preSign(signatureFactory, localSignatureId, signingCertificateChain, references, objects);
        }

        /*
         * ds:SignedInfo
         */
        SignatureMethod signatureMethod = signatureFactory.newSignatureMethod(
            getSignatureMethod(hashAlgo), null);
        CanonicalizationMethod canonicalizationMethod = signatureFactory
            .newCanonicalizationMethod(getCanonicalizationMethod(),
            (C14NMethodParameterSpec) null);
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
            canonicalizationMethod, signatureMethod, references);

        /*
         * JSR105 ds:Signature creation
         */
        String signatureValueId = localSignatureId + "-signature-value";
        javax.xml.crypto.dsig.XMLSignature xmlSignature = signatureFactory
            .newXMLSignature(signedInfo, null, objects, localSignatureId,
            signatureValueId);

        /*
         * ds:Signature Marshalling.
         */
        DOMXMLSignatureIf domXmlSignature;
        try {
            domXmlSignature = HorribleProxy.newProxy(DOMXMLSignatureIf.class, xmlSignature);
        } catch (Exception e) {
            throw new RuntimeException("DomXmlSignature instance error: " + e.getMessage(), e);
        }
        
        domXmlSignature.marshal(doc, this.signatureNamespacePrefix, (DOMCryptoContext) xmlSignContext);

        registerIds(doc);
        Element el = doc.getElementById("idPackageObject");
        if (el != null) {
            el.setAttributeNS(Constants.NamespaceSpecNS, "xmlns:mdssi", PackageNamespaces.DIGITAL_SIGNATURE);
        }

        
        /*
         * Completion of undigested ds:References in the ds:Manifests.
         */
        for (XMLObject object : objects) {
            LOG.log(POILogger.DEBUG, "object java type: " + object.getClass().getName());
            List<XMLStructure> objectContentList = object.getContent();
            for (XMLStructure objectContent : objectContentList) {
                LOG.log(POILogger.DEBUG, "object content java type: " + objectContent.getClass().getName());
                if (!(objectContent instanceof Manifest)) continue;
                Manifest manifest = (Manifest) objectContent;
                List<Reference> manifestReferences = manifest.getReferences();
                for (Reference manifestReference : manifestReferences) {
                    if (manifestReference.getDigestValue() != null) continue;

                    DOMReferenceIf manifestDOMReference;
                    try {
                        manifestDOMReference = HorribleProxy.newProxy(DOMReferenceIf.class, manifestReference);
                    } catch (Exception e) {
                        throw new RuntimeException("DOMReference instance error: " + e.getMessage(), e);
                    }
                    manifestDOMReference.digest(xmlSignContext);
                }
            }
        }

        /*
         * Completion of undigested ds:References.
         */
        List<Reference> signedInfoReferences = signedInfo.getReferences();
        for (Reference signedInfoReference : signedInfoReferences) {
            DOMReferenceIf domReference;
            try {
                domReference = HorribleProxy.newProxy(DOMReferenceIf.class, signedInfoReference);
            } catch (Exception e) {
                throw new RuntimeException("DOMReference instance error: " + e.getMessage(), e);
            }

            // ds:Reference with external digest value
            if (domReference.getDigestValue() != null) continue;
            
            domReference.digest(xmlSignContext);
        }

        /*
         * Calculation of XML signature digest value.
         */
        DOMSignedInfoIf domSignedInfo;
        try {
            domSignedInfo = HorribleProxy.newProxy(DOMSignedInfoIf.class, signedInfo); 
        } catch (Exception e) {
            throw new RuntimeException("DOMSignedInfo instance error: " + e.getMessage(), e);
        }
        
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        domSignedInfo.canonicalize(xmlSignContext, dataStream);
        byte[] octets = dataStream.toByteArray();

        sigDoc = SignatureDocument.Factory.parse(doc.getDocumentElement());
        
        
        /*
         * TODO: we could be using DigestOutputStream here to optimize memory
         * usage.
         */

        MessageDigest jcaMessageDigest = CryptoFunctions.getMessageDigest(hashAlgo);
        byte[] digestValue = jcaMessageDigest.digest(octets);
        return digestValue;
    }

    /**
     * the resulting document needs to be tweaked before it can be digested -
     * this applies to the verification and signing step
     *
     * @param doc
     */
    public void registerIds(Document doc) {
        NodeList nl = doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Object");
        registerIdAttribute(nl);
        nl = doc.getElementsByTagNameNS("http://uri.etsi.org/01903/v1.3.2#", "SignedProperties");
        registerIdAttribute(nl);
    }
    
    protected void registerIdAttribute(NodeList nl) {
        for (int i=0; i<nl.getLength(); i++) {
            Element el = (Element)nl.item(i);
            if (el.hasAttribute("Id")) {
                el.setIdAttribute("Id", true);
            }
        }
    }
    
    private void addDigestInfosAsReferences(List<DigestInfo> digestInfos,
        XMLSignatureFactory signatureFactory, List<Reference> references)
        throws NoSuchAlgorithmException,
        InvalidAlgorithmParameterException, MalformedURLException {
        if (digestInfos == null) return;
        for (DigestInfo digestInfo : digestInfos) {
            byte[] documentDigestValue = digestInfo.digestValue;

            DigestMethod digestMethod = signatureFactory.newDigestMethod(
                            digestInfo.hashAlgo.xmlSignUri, null);

            String uri = new File(digestInfo.description).getName();

            Reference reference = signatureFactory.newReference(uri,
                            digestMethod, null, null, null, documentDigestValue);
            references.add(reference);
        }
    }

    private String getSignatureMethod(HashAlgorithm hashAlgo) {
        if (null == hashAlgo) {
            throw new RuntimeException("digest algo is null");
        }

        XMLSignatureIf XmlSignature;
        try {
            XmlSignature = HorribleProxy.newProxy(XMLSignatureIf.class);
        } catch (Exception e) {
            throw new RuntimeException("JDK doesn't support XmlSignature", e);
        }
            
        switch (hashAlgo) {
        case sha1:   return XmlSignature.ALGO_ID_SIGNATURE_RSA_SHA1();
        case sha256: return XmlSignature.ALGO_ID_SIGNATURE_RSA_SHA256();
        case sha384: return XmlSignature.ALGO_ID_SIGNATURE_RSA_SHA384();
        case sha512: return XmlSignature.ALGO_ID_SIGNATURE_RSA_SHA512();
        case ripemd160: return XmlSignature.ALGO_ID_MAC_HMAC_RIPEMD160();
        default: break;
        }

        throw new RuntimeException("unsupported sign algo: " + hashAlgo);
    }

    /**
     * Gives back the used XAdES signature facet.
     * 
     * @return
     */
    protected XAdESSignatureFacet getXAdESSignatureFacet() {
        return this.xadesSignatureFacet;
    }

    public String getFilesDigestAlgorithm() {
        return null;
    }
    
    public SignatureDocument getSignatureDocument() {
        return sigDoc;
    }

    protected String getCanonicalizationMethod() {
        return CanonicalizationMethod.INCLUSIVE;
    }

    protected void writeDocument() throws IOException {
        XmlOptions xo = new XmlOptions();
        Map<String,String> namespaceMap = new HashMap<String,String>();
        for (SignatureFacet sf : this.signatureFacets) {
            Map<String,String> sfm = sf.getNamespacePrefixMapping();
            if (sfm != null) {
                namespaceMap.putAll(sfm);
            }
        }
        xo.setSaveSuggestedPrefixes(namespaceMap);
        xo.setUseDefaultNamespace();

        LOG.log(POILogger.DEBUG, "output signed Office OpenXML document");

        /*
         * Copy the original OOXML content to the signed OOXML package. During
         * copying some files need to changed.
         */
        OPCPackage pkg = this.getOfficeOpenXMLDocument();

        PackagePartName sigPartName, sigsPartName;
        try {
            // <Override PartName="/_xmlsignatures/sig1.xml" ContentType="application/vnd.openxmlformats-package.digital-signature-xmlsignature+xml"/>
            sigPartName = PackagingURIHelper.createPartName("/_xmlsignatures/sig1.xml");
            // <Default Extension="sigs" ContentType="application/vnd.openxmlformats-package.digital-signature-origin"/>
            sigsPartName = PackagingURIHelper.createPartName("/_xmlsignatures/origin.sigs");
        } catch (InvalidFormatException e) {
            throw new IOException(e);
        }
        
        String sigContentType = "application/vnd.openxmlformats-package.digital-signature-xmlsignature+xml";
        PackagePart sigPart = pkg.getPart(sigPartName);
        if (sigPart == null) {
            sigPart = pkg.createPart(sigPartName, sigContentType);
        }
        
        OutputStream os = sigPart.getOutputStream();
        sigDoc.save(os, xo);
        os.close();
        
        String sigsContentType = "application/vnd.openxmlformats-package.digital-signature-origin";
        PackagePart sigsPart = pkg.getPart(sigsPartName);
        if (sigsPart == null) {
            // touch empty marker file
            sigsPart = pkg.createPart(sigsPartName, sigsContentType);
        }
        
        PackageRelationshipCollection relCol = pkg.getRelationshipsByType(PackageRelationshipTypes.DIGITAL_SIGNATURE_ORIGIN);
        for (PackageRelationship pr : relCol) {
            pkg.removeRelationship(pr.getId());
        }
        pkg.addRelationship(sigsPartName, TargetMode.INTERNAL, PackageRelationshipTypes.DIGITAL_SIGNATURE_ORIGIN);
        
        sigsPart.addRelationship(sigPartName, TargetMode.INTERNAL, PackageRelationshipTypes.DIGITAL_SIGNATURE);
    }
}