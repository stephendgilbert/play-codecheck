package controllers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import models.CodeCheck;
import models.LTI;
import models.Problem;
import models.ProblemData;
import models.S3Connection;
import models.Util;
import play.Logger;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class LTIProblem extends Controller {
	@Inject private S3Connection s3conn;
	@Inject private LTI lti;
	@Inject private CodeCheck codeCheck;
	
	private static Logger.ALogger logger = Logger.of("com.horstmann.codecheck");
	
	private ObjectNode ltiNode(Http.Request request) {
	 	Map<String, String[]> postParams = request.body().asFormUrlEncoded();
	 	if (!lti.validate(request)) throw new IllegalArgumentException("Failed OAuth validation");
	 	
    	String userID = Util.getParam(postParams, "user_id");
		if (Util.isEmpty(userID)) throw new IllegalArgumentException("No user id");

		String toolConsumerID = Util.getParam(postParams, "tool_consumer_instance_guid");
		String contextID = Util.getParam(postParams, "context_id");
		String resourceLinkID = Util.getParam(postParams, "resource_link_id");

		String resourceID = toolConsumerID + "/" + contextID + "/" + resourceLinkID; 
	    

    	ObjectNode ltiNode = JsonNodeFactory.instance.objectNode();

		ltiNode.put("lis_outcome_service_url", Util.getParam(postParams, "lis_outcome_service_url"));		
		ltiNode.put("lis_result_sourcedid", Util.getParam(postParams, "lis_result_sourcedid"));		
		ltiNode.put("oauth_consumer_key", Util.getParam(postParams, "oauth_consumer_key"));		

		ltiNode.put("submissionID", resourceID + " " + userID);		
		ltiNode.put("retrieveURL", "/lti/retrieve");
		ltiNode.put("sendURL", "/lti/send");
		
		return ltiNode;
	}
	
	private String rewriteRelativeLinks(String urlString) throws IOException {
		URL url = new URL(urlString);
		InputStream in = url.openStream();
		String contents = new String(Util.readAllBytes(in), StandardCharsets.UTF_8);
		in.close();
		int i1 = urlString.indexOf("/", 8); // after https://
		String domain = urlString.substring(0, i1);
		
		Pattern pattern = Pattern.compile("\\s+(src|href)=[\"']([^\"']+)[\"']");
		Matcher matcher = pattern.matcher(contents);
		int previousEnd = 0;
		String document = "";
		while (matcher.find()) {
			int start = matcher.start();
			document += contents.substring(previousEnd, start);
			String group1 = matcher.group(1);
			String group2 = matcher.group(2);
			document += " " + group1 + "='";
			if (group2.startsWith("http:") || group2.startsWith("https:") || group2.startsWith("data:"))
				document += group2;
			else if (group2.startsWith("/"))
				document += domain + "/" + group2;
			else if (group2.equals("assets/receiveMessage.js")){ // TODO: Hack?
				document += "/" + group2;
			} else {
				int i = urlString.lastIndexOf("/");
				document += urlString.substring(0, i + 1) + group2;
			}				
			document += "'";
			previousEnd = matcher.end();
		}			
		document += contents.substring(previousEnd);		
		return document;
	}

    public Result launch(Http.Request request) throws IOException {    
		try {
			ObjectNode ltiNode = ltiNode(request);
			
		    String qid = request.queryString("qid").orElse(null);
		    // TODO: What about CodeCheck qids?
	    	if (qid == null) return badRequest("No qid");
			String domain = "https://www.interactivities.ws";
			String urlString = domain + "/" + qid + ".xhtml";
			String document = rewriteRelativeLinks(urlString);
			document = document.replace("<head>", "<head><script>const lti = " + ltiNode.toString() + "</script>");
			return ok(document).as("text/html");
		} catch (Exception ex) {
			logger.info(Util.getStackTrace(ex));
			return badRequest(ex.getMessage());
		}
 	}		
    
    public Result launchCodeCheck(Http.Request request, String repo, String problemName) {
    	try {    		
    		// TODO: Now the client will do the LTI communication. CodeCheck should do it.
			ObjectNode ltiNode = ltiNode(request);
			String ccu = ltiNode.get("submissionID").asText(); 
			Path problemPath = codeCheck.loadProblem(repo, problemName, ccu);
	        Problem problem = new Problem(problemPath);
	        ProblemData data = problem.getData();	        
    		ObjectNode problemNode = (ObjectNode) Json.toJson(problem.getData());
    		problemNode.put("url", "/checkNJS"); 
    		problemNode.put("repo", repo);
    		problemNode.put("problem", problemName);
    		problemNode.remove("description"); // TODO: Or let node render it? 
    		String qid = "codecheck-" + repo + "-" + problemName;
    		String document = "<?xml version='1.0' encoding='UTF-8'?>\n" + 
				"<html xmlns='http://www.w3.org/1999/xhtml'>\n" + 
				"  <head>\n" + 
				"    <meta http-equiv='content-type' content='text/html; charset=UTF-8'/>\n" + 
				"    <title>Interactivities</title> \n" + 
				"    <script type='text/javascript' src='https://www.interactivities.ws/script/horstmann_all_min.js'></script> \n" + 
				"    <link type='text/css' rel='stylesheet' href='https://www.interactivities.ws/css/horstmann_all_min.css'></link>\n" + 
				"    <style type='text/css'>\n" + 
				"      ol.interactivities > li {\n" + 
				"        list-style: none;\n" + 
				"        margin-bottom: 2em;\n" + 
 				"      }\n" + 
				"      body {\n" + 
				"        margin-left: 2em;\n" + 
				"        margin-right: 2em;\n" + 
				"        overflow-y: visible;\n" + 
				"      }\n" + 
				"    </style>\n" + 
				"    <script type='text/javascript'>//<![CDATA[\n" +
				"const lti = " + ltiNode.toString() + 
				"\nhorstmann_codecheck.setup.push(\n" +
				problemNode.toString() + 
				")\n" +
				"\n//]]></script>\n" + 
				"    <script type='text/javascript' src='/assets/receiveMessage.js'></script> \n" + 
				"  </head> \n" + 
				"  <body>\n" + 
				"    <ol class='interactivities' id='interactivities'>\n" +
				"      <li title='" + qid + "' id='" + qid + "'>\n" + 
				"        <div class='hc-included'>\n" +
				(data.description == null ? "" : data.description) +
				"        </div>\n" + 
				"        <div class='horstmann_codecheck'>\n" +
				"        </div>\n" + 
				"      </li>\n" + 
				"    </ol>" +
				"  </body>" +
				"</html>";
    		return ok(document).as("text/html");
    	}  catch (Exception ex) {
			logger.info(Util.getStackTrace(ex));
			return badRequest(ex.getMessage());
		}
    }
	
	public Result send(Http.Request request) throws IOException, NoSuchAlgorithmException {
		try {
			ObjectNode requestNode = (ObjectNode) request.body().asJson();
	    	Instant now = Instant.now();
			String submissionID = requestNode.get("submissionID").asText();
			ObjectNode submissionNode = JsonNodeFactory.instance.objectNode();
			submissionNode.put("submissionID", submissionID);
			submissionNode.put("submittedAt", now.toString());
			submissionNode.put("state", requestNode.get("state").toString());
			double score = requestNode.get("score").asDouble();			
			submissionNode.put("score", score);
			s3conn.writeJsonObjectToDynamoDB("CodeCheckSubmissions", submissionNode);
			
	        String outcomeServiceUrl = requestNode.get("lis_outcome_service_url").asText();
			String sourcedID = requestNode.get("lis_result_sourcedid").asText();
			String oauthConsumerKey = requestNode.get("oauth_consumer_key").asText();						
	        lti.passbackGradeToLMS(outcomeServiceUrl, sourcedID, score, oauthConsumerKey); 
			
			return ok("");
        } catch (Exception e) {
            logger.error(Util.getStackTrace(e));
            return badRequest(e.getMessage());
        }
	}
	
	public Result retrieve(Http.Request request) throws IOException {
		try {
			ObjectNode requestNode = (ObjectNode) request.body().asJson();
			String submissionID = requestNode.get("submissionID").asText();
			ObjectNode result = s3conn.readJsonObjectFromDynamoDB("CodeCheckSubmissions", "submissionID", submissionID);
			ObjectMapper mapper = new ObjectMapper();
			result.set("state", mapper.readTree(result.get("state").asText()));
			return ok(result);
	    } catch (Exception e) {
	        logger.error(Util.getStackTrace(e));
	        return badRequest(e.getMessage());
	    }
	}	
}