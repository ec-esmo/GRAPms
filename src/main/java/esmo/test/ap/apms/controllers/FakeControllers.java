/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package esmo.test.ap.apms.controllers;

import java.io.IOException;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import esmo.test.ap.apms.MemCacheConfig;
import esmo.test.ap.apms.model.factories.EsmoResponseFactory;
import esmo.test.ap.apms.model.pojo.Amka;
import esmo.test.ap.apms.model.pojo.AttributeSet;
import esmo.test.ap.apms.model.pojo.AttributeType;
import esmo.test.ap.apms.model.pojo.EntityMetadata;
import esmo.test.ap.apms.model.pojo.GrantRequest;
import esmo.test.ap.apms.model.pojo.MinEduResponse;
import esmo.test.ap.apms.model.pojo.MinEduResponse.InspectionResult;
import esmo.test.ap.apms.model.pojo.QueryIdResponse;
import esmo.test.ap.apms.model.pojo.SessionMngrResponse;
import esmo.test.ap.apms.model.pojo.TokenResponse;
import esmo.test.ap.apms.model.pojo.UpdateDataRequest;
import esmo.test.ap.apms.service.EsmoMetadataService;
import esmo.test.ap.apms.service.HttpSignatureService;
import esmo.test.ap.apms.service.KeyStoreService;
import esmo.test.ap.apms.service.NetworkService;
import esmo.test.ap.apms.service.ParameterService;
import esmo.test.ap.apms.service.impl.HttpSignatureServiceImpl;
import esmo.test.ap.apms.service.impl.NetworkServiceImpl;
import esmo.test.ap.apms.utils.DateParsingUtils;
import esmo.test.ap.apms.utils.EidasNamesUtils;
import esmo.test.ap.apms.utils.StringDistance;

/**
 *
 * @author nikos
 */
@Controller
public class FakeControllers {

    private final NetworkService netServ;
    private final ParameterService paramServ;
    private final EsmoMetadataService metadataServ;
    private final KeyStoreService keyServ;

    private final static Logger LOG = LoggerFactory.getLogger(FakeControllers.class);

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    public FakeControllers(ParameterService paramServ, KeyStoreService keyServ,
            EsmoMetadataService metadataServ) throws InvalidKeySpecException, KeyStoreException, IOException, NoSuchAlgorithmException, UnrecoverableKeyException {
        this.paramServ = paramServ;
        this.metadataServ = metadataServ;
        this.keyServ = keyServ;
        Key signingKey = this.keyServ.getSigningKey();
        String fingerPrint = "7a9ba747ab5ac50e640a07d90611ce612b7bde775457f2e57b804517a87c813b";
        HttpSignatureService httpSigServ = new HttpSignatureServiceImpl(fingerPrint, signingKey);
        this.netServ = new NetworkServiceImpl(httpSigServ);
    }

    @RequestMapping(value = "/ap/query", method = {RequestMethod.POST, RequestMethod.GET})
    public String queryAp(@RequestParam(value = "msToken", required = true) String msToken, Model model) throws KeyStoreException {
        String sessionMngrUrl = paramServ.getParam("SESSION_MANAGER_URL");

        Cache memCache = this.cacheManager.getCache(MemCacheConfig.AP_SESSION);

        List<NameValuePair> requestParams = new ArrayList<NameValuePair>();
        requestParams.add(new NameValuePair("token", msToken));
        ObjectMapper mapper = new ObjectMapper();
        try {
            SessionMngrResponse resp = mapper.readValue(netServ.sendGet(sessionMngrUrl, "/sm/validateToken", requestParams, 1), SessionMngrResponse.class);
            if (resp.getCode().toString().equals("OK") && StringUtils.isEmpty(resp.getError())) {
                String sessionId = resp.getSessionData().getSessionId();
                String apMsSessionId = UUID.randomUUID().toString();

                //calls SM, “/sm/getSessionData” to get the session object that must contain the variables apRequest, apMetadata 
                requestParams.clear();
                requestParams.add(new NameValuePair("sessionId", sessionId));
                resp = mapper.readValue(netServ.sendGet(sessionMngrUrl, "/sm/getSessionData", requestParams, 1), SessionMngrResponse.class);

                String apRequest = (String) resp.getSessionData().getSessionVariables().get("apRequest");

                if (apRequest == null) {
                    LOG.error("no apRequest found in session " + sessionId);
                    return "errorPage";
                } else {

                    EntityMetadata apMsMetadata = metadataServ.getMetadata();

                    AttributeSet parsedApRequest = mapper.readValue(apRequest, AttributeSet.class);
                    List<AttributeType> matchingRequestedAttributes = Arrays.stream(parsedApRequest.getAttributes()).filter(attribute -> {
                        return Arrays.asList(apMsMetadata.getClaims()).contains(attribute.getFriendlyName());
                    }).collect(Collectors.toList());

                    if (matchingRequestedAttributes.size() > 0) {
                        memCache.put(apMsSessionId, mapper.writeValueAsString(resp)); //save the whole ESMO session in cache
                        Amka amkaForm = new Amka();
                        amkaForm.setSessionId(apMsSessionId);
                        model.addAttribute("amka", amkaForm);
                        return "amka";
                    }
                    LOG.error("Error, no supported attributes were found in the request");
                    Arrays.stream(parsedApRequest.getAttributes()).forEach(attr -> {
                        LOG.error(attr.getFriendlyName());
                    });

                }
            } else {
                LOG.error("something wring with the SM session!");
                LOG.error(resp.getError());
                LOG.error(resp.getCode().toString());
            }

        } catch (IOException ex) {
            LOG.info(ex.getMessage());
        } catch (NoSuchAlgorithmException ex) {
            LOG.info(ex.getMessage());
        }

        return "errorPage";
    }

    @RequestMapping(value = "/ap/forward", method = {RequestMethod.POST})
    public String forwardToACM(@ModelAttribute("amka") @Valid Amka amka, BindingResult bindingResult,
            Model model) throws KeyStoreException, IOException, NoSuchAlgorithmException {

        if (bindingResult.hasErrors()) {
            return "amka";
        }
        
        String acmName = paramServ.getParam("ACM_NAME");
        String apMsName = paramServ.getParam("AP_MS_NAME");
        List<NameValuePair> requestParams = new ArrayList<NameValuePair>();
        ObjectMapper mapper = new ObjectMapper();
        Cache memCache = this.cacheManager.getCache(MemCacheConfig.AP_SESSION);
        String esmoSession = (String) memCache.get(amka.getSessionId()).get();
        SessionMngrResponse resp = mapper.readValue(esmoSession, SessionMngrResponse.class);
        String esmoSessionId = resp.getSessionData().getSessionId();
        String sessionMngrUrl = paramServ.getParam("SESSION_MANAGER_URL");
        String authenticationSet = (String) resp.getSessionData().getSessionVariables().get("authenticationSet");
        // ""{"id":"id","type":"Response","issuer":"issuer","recipient":"recipient","attributes":[{"name":"http://eidas.europa.eu/attributes/naturalperson/CurrentFamilyName","friendlyName":"FamilyName","encoding":"UTF-8","language":"N/A","isMandatory":true,"values":["cph8"]},{"name":"http://eidas.europa.eu/attributes/naturalperson/CurrentGivenName","friendlyName":"FirstName","encoding":"UTF-8","language":"N/A","isMandatory":true,"values":["cph8"]},{"name":"http://eidas.europa.eu/attributes/naturalperson/DateOfBirth","friendlyName":"DateOfBirth","encoding":"UTF-8","language":"N/A","isMandatory":true,"values":["1966-01-01"]},{"name":"http://eidas.europa.eu/attributes/naturalperson/PersonIdentifier","friendlyName":"PersonIdentifier","encoding":"UTF-8","language":"N/A","isMandatory":true,"values":["CA/CA/Cph123456"]}],"properties":{"NameID":"CA/CA/Cph123456","levelOfAssurance":"http://eidas.europa.eu/LoA/low"},"inResponseTo":null,"loa":"low","notBefore":null,"notAfter":null,"status":{"code":"OK","subcode":null,"message":null}}""

        // check existence of eIDAS attributes
        AttributeSet eIDASAttributes = mapper.readValue(authenticationSet, AttributeSet.class);
        AttributeType dateOfBirth = new AttributeType();
        AttributeType eidasGivenName = new AttributeType();
        AttributeType eidasFamilyName = new AttributeType();

        Arrays.asList(eIDASAttributes.getAttributes()).stream().forEach(aType -> {
            if (aType.getFriendlyName().equals("DateOfBirth")) {
                dateOfBirth.setValues(aType.getValues());
            }
            if (aType.getFriendlyName().equals("FamilyName")) {
                eidasFamilyName.setValues(aType.getValues());
            }
            if (aType.getFriendlyName().equals("FirstName")) {
                eidasGivenName.setValues(aType.getValues());
            }
        });

        // store the ap response
        String attributSetString = null;
        if (dateOfBirth.getValues() == null || eidasGivenName.getValues() == null || eidasFamilyName.getValues() == null) {
            LOG.error("no date of birth was present is  eIDAS attributes");
            AttributeSet result = EsmoResponseFactory.buildErrorResponse(apMsName, acmName, resp.getSessionData().getSessionId());
            attributSetString = mapper.writeValueAsString(result);

        } else {
            //fetch AP response from minEdu API
            // wrap response to ESMO response
            try {
                // check name and date of birth from amka!
                Date amkaDate = DateParsingUtils.parseAmkaDate(amka.amkaNumber);
                Date eidasDate = DateParsingUtils.parseEidasDate(dateOfBirth.getValues()[0]);

                String qId = amka.getAcademicId();
                if (amkaDate.compareTo(eidasDate) == 0) {
                	qId = amka.getAmkaNumber();
                }
                
                // call minEdu api with amka to get studentId, or use studentId if present
            	String minEduTokenUri = paramServ.getParam("MINEDU_TOKEN_URL");
            	String minEduTokenUser = paramServ.getParam("MINEDU_TOKEN_USERNAME");
            	String minEduTokenPass = paramServ.getParam("MINEDU_TOKEN_PASSWORD");
            	String minEduTokenGrantType = paramServ.getParam("MINEDU_TOKEN_GRANTTYPE");
            	GrantRequest grantReq = new GrantRequest(minEduTokenUser, minEduTokenPass, minEduTokenGrantType);
            	RestTemplate restTemplate = new RestTemplate();
    	        TokenResponse tokResp = restTemplate.postForObject(minEduTokenUri, grantReq, TokenResponse.class);
    	        
    	        if (tokResp != null && tokResp.getSuccess().equals("true") && tokResp.getOauth() != null && tokResp.getOauth().getAccessToken() != null) {
    	        	String minEduQueryIdUrl = paramServ.getParam("MINEDU_QUERYID_URL") + "?id=" + qId + "&username=" + minEduTokenUser + "&password=" + minEduTokenPass;
    	        	HttpHeaders requestHeaders = new HttpHeaders();
    	        	requestHeaders.add("Authorization", "Bearer " + tokResp.getOauth().getAccessToken());
    	        	HttpEntity<?> entity = new HttpEntity<>(requestHeaders);
    	        	ResponseEntity<QueryIdResponse> queryId = restTemplate.exchange(minEduQueryIdUrl, HttpMethod.GET, entity, QueryIdResponse.class);
    	        	QueryIdResponse qResp = queryId.getBody();
    	        	esmo.test.ap.apms.model.pojo.InspectionResult ir = qResp.getInspectionResult();
    	        	
    	        	
    	        	InspectionResult inspResult = new InspectionResult(
	        			ir.getAcademicId(), // academicId 
	        			ir.getAmka(), // amka 
	        			ir.getResidenceLocation(), // residenceLocation 
	        			ir.getUniversityLocation(), // universityLocation 
	        			ir.getStudentshipType(), // studentshipType 
	        			ir.getGreekFirstName(), // greekFirstName 
	        			ir.getGreekLastName(), // greekLastName 
	        			ir.getLatinFirstName(), // latinFirstName 
	        			ir.getLatinLastName(), // latinLastName 
	        			ir.getDepartmentName(), // departmentName 
	        			ir.getEntryYear().toString(), // entryYear  
	        			ir.getCurrentSemester().toString(), // currentSemester 
	        			ir.getPostGraduateProgram(), // postGraduateProgram 
	        			ir.getPasoValidity(), // pasoValidity 
	        			ir.getPasoExpirationDate(), // pasoExpirationDate 
	        			ir.getSubmissionDate(), // submissionDate 
	        			ir.getApplicationStatus(), // applicationStatus 
	        			ir.getCancellationDate(), // cancellationDate 
	        			ir.getCancellationReason(), // cancellationReason 
	        			ir.getErasmus(), // erasmus 
	        			ir.getStudentNumber(), // studentNumber 
	        			ir.getPhotoUrl(), // photoUrl 
	        			ir.getWebServiceSuccess().toString(), // webServiceSuccess 
	        			ir.getValidationError() // validationError
        			);
    	        	/*InspectionResult inspResult = new InspectionResult("273004078833", null, "ΣΑΜΟΥ - ΣΑΜΟΥ",
                            "ΧΙΟΥ - ΧΙΟΥ", "Προπτυχιακός", "ΜΑΡΙΑ", "ΠΑΠΑΓΕΩΡΓΙΟΥ", "MARIA", "PAPAGEORGIOU", "ΜΗΧΑΝΙΚΩΝ ΟΙΚΟΝΟΜΙΑΣ ΚΑΙ ΔΙΟΙΚΗΣΗΣ (ΠΑΝΕΠΙΣΤΗΜΙΟ ΑΙΓΑΙΟΥ)", "2014",
                            "3", "", "ΝΑΙ", "31/08/2021", "22/10/2015", "Η Ακαδημαϊκή Ταυτότητα παραδόθηκε στο δικαιούχο",
                            "", "Δεν έχει ακυρωθεί", "ΟΧΙ", "2312014103", "", "true", null);*/
    	        	
                    MinEduResponse response = new MinEduResponse("success", inspResult);

                    if (StringDistance.areSimilar(EidasNamesUtils.getLatin(eidasGivenName.getValues()[0]), inspResult.getLatinFirstName())
                            && StringDistance.areSimilar(EidasNamesUtils.getLatin(eidasFamilyName.getValues()[0]), inspResult.getLatinLastName())) {
                        AttributeSet result = EsmoResponseFactory.buildFromMinEduResponse(response, apMsName, acmName, resp.getSessionData().getSessionId());
                        attributSetString = mapper.writeValueAsString(result);
                    } else {
                        LOG.error("eidas name and amka names do not match!!");
                        LOG.error(" eidasName " + eidasGivenName.getValues()[0] + " -- " + inspResult.getLatinFirstName());
                        LOG.error(" eidasFamilyName " + eidasFamilyName.getValues()[0] + " --  " + inspResult.getLatinLastName());
                        AttributeSet result = EsmoResponseFactory.buildErrorResponse(apMsName, acmName, resp.getSessionData().getSessionId());
                        attributSetString = mapper.writeValueAsString(result);
                    }
    	        }
            } catch (ParseException e) {
                LOG.error("could not parse date " + amka.amkaNumber + " " + dateOfBirth.getValues()[0]);
                LOG.error(e.getMessage());
                AttributeSet result = EsmoResponseFactory.buildErrorResponse(apMsName, acmName, resp.getSessionData().getSessionId());
                attributSetString = mapper.writeValueAsString(result);
            }
        }

        requestParams.clear();
        requestParams.add(new NameValuePair("dataObject", attributSetString));
        requestParams.add(new NameValuePair("variableName", "dsResponse"));
        requestParams.add(new NameValuePair("sessionId", esmoSessionId));
        UpdateDataRequest updateReq = new UpdateDataRequest(esmoSessionId, "dsResponse", attributSetString);
        resp = mapper.readValue(netServ.sendPostBody(sessionMngrUrl, "/sm/updateSessionData", updateReq, "application/json", 1), SessionMngrResponse.class);

        if (!resp.getCode().toString().equals("OK")) {
            LOG.error("ERROR: " + resp.getError());
            return "errorPage";
        }

        // store the ap metadata
        requestParams.clear();
        if (metadataServ != null && metadataServ.getMetadata() != null) {
            updateReq = new UpdateDataRequest(esmoSessionId, "dsMetadata", mapper.writeValueAsString(metadataServ.getMetadata()));
            resp = mapper.readValue(netServ.sendPostBody(sessionMngrUrl, "/sm/updateSessionData", updateReq, "application/json", 1), SessionMngrResponse.class);
            if (!resp.getCode().toString().equals("OK")) {
                LOG.error("ERROR: " + resp.getError());
                return "errorPage";
            }
        }
        //generate jwt and redirect to acm
        requestParams.clear();
        requestParams.add(new NameValuePair("sessionId", esmoSessionId));
        requestParams.add(new NameValuePair("sender", paramServ.getParam("REDIRECT_JWT_SENDER"))); //[TODO] add correct sender "IdPms001"
        requestParams.add(new NameValuePair("receiver", paramServ.getParam("REDIRECT_JWT_RECEIVER"))); //"ACMms001"
        resp = mapper.readValue(netServ.sendGet(sessionMngrUrl, "/sm/generateToken", requestParams, 1), SessionMngrResponse.class);
        if (!resp.getCode().toString().equals("NEW")) {
            LOG.error("ERROR: " + resp.getError());
            return "errorPage";
        } else {
            String msToken = resp.getAdditionalData();
            //IdP calls, post  /acm/response
            String acmUrl = paramServ.getParam("ACM_URL");
            model.addAttribute("msToken", msToken);
            model.addAttribute("acmUrl", acmUrl + "/acm/response");
            return "acmRedirect";
        }
    }
}
