package com.menix.jobassistant;

import org.springframework.stereotype.Service;
import java.net.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

@Service
public class SourceRegistryService {
  private final JobSourceConnectionRepository repository;
  public SourceRegistryService(JobSourceConnectionRepository repository) {
    this.repository=repository;
  }
  public List<JobSourceConnection> list(){return repository.findAll().stream().sorted(Comparator.comparing(JobSourceConnection::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();}
  public JobSourceConnection add(Map<String,Object> body) {
    String raw=Objects.toString(body.get("url"),"").trim(); String name=Objects.toString(body.get("name"),"").trim();
    validatePublicHttps(raw);
    if(name.isBlank()) name=hostName(raw);
    if(repository.findBySourceUrl(raw).isPresent()) throw new IllegalArgumentException("This URL is already in your Sources list.");
    JobSourceConnection s=new JobSourceConnection(); s.setName(name); s.setSourceUrl(raw);
    s.setSourceType(Objects.toString(body.getOrDefault("type","CAREER_SITE"),"CAREER_SITE"));
    s.setTermsNotes(Objects.toString(body.getOrDefault("termsNotes","Verify provider terms and permissions before enabling automated retrieval."),""));
    s.setRateLimitNotes(Objects.toString(body.getOrDefault("rateLimitNotes","Honor provider rate limits, HTTP 429, and Retry-After."),""));
    s.setDetectedFormat(detectByUrl(raw)); s.setConnectionStatus("NOT_TESTED"); return repository.save(s);
  }
  public JobSourceConnection update(Long id, Map<String,Object> body) {
    JobSourceConnection s=repository.findById(id).orElseThrow(()->new NoSuchElementException("Source not found"));
    if(body.containsKey("enabled")) s.setEnabled(Boolean.parseBoolean(Objects.toString(body.get("enabled"))));
    if(body.containsKey("name")) s.setName(Objects.toString(body.get("name"),s.getName()));
    if(body.containsKey("termsNotes")) s.setTermsNotes(Objects.toString(body.get("termsNotes"),""));
    if(body.containsKey("rateLimitNotes")) s.setRateLimitNotes(Objects.toString(body.get("rateLimitNotes"),""));
    return repository.save(s);
  }
  public void delete(Long id){if(!repository.existsById(id))throw new NoSuchElementException("Source not found");repository.deleteById(id);}
  public Map<String,Object> test(Long id) {
    JobSourceConnection s=repository.findById(id).orElseThrow(()->new NoSuchElementException("Source not found"));
    Map<String,Object> result=new LinkedHashMap<>(); result.put("id",id); result.put("testedAt",LocalDateTime.now().toString());
    try {
      validatePublicHttps(s.getSourceUrl());
      URLConnection opened=URI.create(s.getSourceUrl()).toURL().openConnection();
      if(!(opened instanceof HttpsURLConnection response))throw new IllegalArgumentException("Only HTTPS source connections are supported.");
      response.setConnectTimeout(5000);response.setReadTimeout(8000);response.setInstanceFollowRedirects(false);
      response.setRequestProperty("User-Agent","MenixJobAssistant/1.0 (+manual source connection test)");
      int statusCode=response.getResponseCode();
      String type=Objects.toString(response.getContentType(),"unknown").toLowerCase(Locale.ROOT);
      String body="";
      try(InputStream stream=statusCode>=400?response.getErrorStream():response.getInputStream()){
        if(stream!=null)body=new String(stream.readNBytes(1024*1024),StandardCharsets.UTF_8);
      }finally{response.disconnect();}
      String detected=detect(type,body,s.getSourceUrl());
      boolean reachable=statusCode>=200&&statusCode<300;
      s.setDetectedFormat(detected); s.setConnectionStatus(reachable?"REACHABLE":"ERROR"); s.setLastError(reachable?"":"HTTP "+statusCode+"; redirects are not followed."); s.setLastTestAt(LocalDateTime.now()); repository.save(s);
      result.put("ok",reachable); result.put("statusCode",statusCode); result.put("contentType",type); result.put("detectedFormat",detected);
      result.put("message",!reachable?"Source returned HTTP "+statusCode+". Redirects are not followed.":detected.equals("CAREER_PAGE")?"Website reachable. No supported feed/API was detected; use the external career-page fallback.":"Website reachable. Detection is advisory; confirm provider terms and schema before enabling imports.");
    } catch(Exception ex) {
      String message=safeError(ex); s.setConnectionStatus("ERROR");s.setLastError(message);s.setLastTestAt(LocalDateTime.now());repository.save(s);
      result.put("ok",false);result.put("message",message);result.put("detectedFormat",s.getDetectedFormat());
    }
    return result;
  }
  private static String detect(String type,String body,String url){String path=url.toLowerCase(Locale.ROOT);String b=body.toLowerCase(Locale.ROOT);
    if(type.contains("json")||path.matches(".*\\.(json)(\\?.*)?$"))return "JSON_API_OR_FEED";
    if(type.contains("xml")||type.contains("rss")||path.contains("rss")||path.contains("atom"))return "RSS_ATOM_FEED";
    if(b.contains("<jobposting")||b.contains("schema.org/jobposting"))return "STRUCTURED_JOB_PAGE";
    if(type.contains("html")||b.contains("<html"))return "CAREER_PAGE";
    return "UNKNOWN";
  }
  private static String detectByUrl(String url){String u=url.toLowerCase(Locale.ROOT);if(u.endsWith(".json")||u.contains("/api/")||u.contains("?format=json"))return "POSSIBLE_JSON_API";if(u.contains("rss")||u.contains("atom")||u.endsWith(".xml"))return "POSSIBLE_RSS_ATOM";return "UNKNOWN";}
  private static String hostName(String raw){try{return URI.create(raw).getHost();}catch(Exception e){return "Manual source";}}
  private static String safeError(Exception e){String m=Objects.toString(e.getMessage(),e.getClass().getSimpleName());return m.length()>500?m.substring(0,500):m;}
  private static void validatePublicHttps(String raw){
    if(raw==null||raw.length()>2048)throw new IllegalArgumentException("Enter a valid HTTPS URL (maximum 2048 characters).");
    URI uri;try{uri=URI.create(raw);}catch(Exception e){throw new IllegalArgumentException("URL format is invalid.");}
    if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("Only public HTTPS URLs are supported. Credentials in URLs are not allowed.");
    String host=uri.getHost().toLowerCase(Locale.ROOT);
    if(host.equals("localhost")||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal")||host.equals("metadata.google.internal"))throw new IllegalArgumentException("Local and internal hostnames are blocked for security.");
    try { for(InetAddress address:InetAddress.getAllByName(host)) if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()) throw new IllegalArgumentException("Private or local network addresses are blocked."); }
    catch(UnknownHostException e){throw new IllegalArgumentException("The host could not be resolved. Check the URL and try again.");}
  }
}
