package com.fce4.dtrtoolkit;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import org.tomlj.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fce4.dtrtoolkit.validators.LegacyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.PostConstruct;

@Service
public class TypeService {

    @Autowired
    private TypeRepository typeRepository;

    @Autowired
    private LegacyValidator legacyValidator;

    private String config="src/main/config/config.toml";
    
    Logger logger = Logger.getLogger(TypeService.class.getName());

    @PostConstruct
    public void init() throws IOException, InterruptedException {
        logger.info(new File(".").getAbsolutePath());
        refreshRepository();
    }

    /**
     * Refreshes the full contents of the cache, harvesting the env_file.
     * @throws InterruptedException
     * @throws IOException
     */
    @Scheduled(fixedRate = 60, timeUnit = TimeUnit.MINUTES)
    public void refreshRepository() throws IOException, InterruptedException{
        logger.info("Refreshing Cache");

        //Setting a new timestamp, should a new logfile be necessary
        Date currentDate = new Date(System.currentTimeMillis());
		SimpleDateFormat df = new SimpleDateFormat("yyyy-MMM");
		System.setProperty("timestamp", df.format(currentDate));

        Path source = Paths.get(config);
        TomlParseResult result = Toml.parse(source);
      
       for(var i : result.entrySet()){
            int counter = 0;
            TomlTable t = TomlTable.class.cast(i.getValue());
            String dtr = i.getKey();
            String uri = t.getString("url");
            String suffix = t.getString("suffix");
            List<Object> types = t.getArray("types").toList();
            String style = t.getString("style");

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .timeout(Duration.ofSeconds(10))
                .uri(URI.create(uri+suffix))
                .build();
                HttpResponse<String> response = client.send(request,HttpResponse.BodyHandlers.ofString());
    
            ObjectMapper mapper = new ObjectMapper();
            JsonNode actualObj = mapper.readTree(response.body());
    
            for (JsonNode jsonNode : actualObj.get("results")) {
                if(!jsonNode.has("type")){
                    continue;
                }
                if(types.contains(jsonNode.get("type").textValue())){
                    TypeEntity typeEntity = new TypeEntity(jsonNode, style, uri);
                    typeRepository.save(typeEntity);
                    counter+=1;
                }
            }
            logger.info(String.format("Added %s types from DTR '%s'.", counter, dtr));
       }
       logger.info("Refreshing Cache successful.");
    }

    /**
     * Adds a single data type to the repository. Either for selective refreshing, or to add valid types not in the configured DTR's.
     * Only works for handle's that forward to a type in a cordra instance.
     * @param identifier the PID to add/refresh in the cache.
     * @throws InterruptedException
     * @throws IOException
     */
    public void addType(String pid) throws IOException, InterruptedException{
        logger.info(String.format("Adding Type %s to the cache", pid));

        String uri = "https://hdl.handle.net/" + pid + "?locatt=view:json";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .GET()
            .timeout(Duration.ofSeconds(10))
            .uri(URI.create(uri))
            .build();
            HttpResponse<String> response = client.send(request,HttpResponse.BodyHandlers.ofString());

        /*After the first request, we receive the URL to the type in its DTR. Since we need the full specification, the parameter "?full"
        needs to be set to true to get all the information necessary. Thus the second request.*/
        
        if(!response.headers().map().containsKey("location")){
            logger.info(String.format("Requested Handle %s does not exist", pid));
            throw new IOException(String.format("Requested Handle %s does not exist.", pid));
        }
        String dtrUrl = response.headers().map().get("location").get(0);
        
        request = HttpRequest.newBuilder()
            .GET()
            .timeout(Duration.ofSeconds(10))
            .uri(URI.create(dtrUrl + "?full=true"))
            .build();
        response = client.send(request,HttpResponse.BodyHandlers.ofString());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(response.body());
        if(!jsonNode.has("id") || !jsonNode.has("type") || !jsonNode.has("content")){
            logger.warning(String.format("Requested Handle %s is not a valid type", pid));
            throw new IOException("Handle is not valid type.");
        }
        TypeEntity typeEntity = new TypeEntity(jsonNode, dtrUrl);
		if(dtrUrl.contains("dtr-test.pidconsortium") || dtrUrl.contains("dtr-pit.pidconsortium")){
			typeEntity.setStyle("legacy");
		}
        typeRepository.save(typeEntity);
        addTags(typeEntity);
    
        logger.info(String.format("Adding Type %s to the cache was successful", pid));
    }

    /**
     * Adds the provided in the type to the TypeRepository
     * @param typeEntity the typeEntity Object whose tags should be added.
     */
    public void addTags(TypeEntity typeEntity){

    }

    /**
     * Retrieve the description of a type from the repo.
     * @param identifier the PID to add/refresh in the cache.
     * @param refresh flag, if type should be refreshed
     * @throws InterruptedException
     * @throws IOException
     */
    public JsonNode getDescription(String pid, Boolean refresh) throws IOException, InterruptedException{
        logger.info(String.format("Getting Type Description for %s.", pid));
        checkAdd(pid, refresh);
        return typeRepository.get(pid).serialize();
    }

    /**
     * Construct and return the validation schema of a type from the repo.
     * @param identifier the PID to add/refresh in the cache.
     * @param refresh flag, if type should be refreshed
     */
    public ObjectNode getValidation(String pid, Boolean refresh) throws IOException, InterruptedException {
        
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        checkAdd(pid, refresh);
        if(typeRepository.get(pid).getStyle().equals("legacy")){
            root = legacyValidator.validation(pid);
        }
        return root;
    }

    /**
     * Helper function avoiding repeated code. Adds a PID to the repo if conditions demand it.
     * @param identifier the PID to add/refresh in the cache.
     * @param refresh flag, if type should be refreshed
     */
    public void checkAdd(String pid, Boolean refresh) throws IOException, InterruptedException {
        if(!typeRepository.hasPid(pid) || refresh){
            logger.info(String.format("Retrieving pid %s via handle and caching...", pid));
            addType(pid);
        }
    }

    /**
     * Search for types in the repository with a query.
     * @param identifier the PID to add/refresh in the cache.
     */
    public void search(String query) {

    }
}