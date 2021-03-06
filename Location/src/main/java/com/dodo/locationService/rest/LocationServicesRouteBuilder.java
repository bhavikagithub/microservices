package com.dodo.locationService.rest;

import static org.apache.camel.model.rest.RestParamType.path;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.spring.SpringRouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.dodo.cassandra.tools.GeoApi;
import com.dodo.cassandra.tools.Tool;
import com.dodo.error.ErrorService;
import com.dodo.hazelcast.HazelcastService;
import com.dodo.model.Geocoding;
import com.dodo.model.Geocodings;
import com.dodo.model.Response;
import com.dodo.mongo.repository.GeocodingRepository;
import com.fasterxml.jackson.core.JsonParseException;


/**
 * Define REST services using the Camel REST DSL
 */
@Configuration
public class LocationServicesRouteBuilder extends SpringRouteBuilder {
	
	private static final Logger logger = LoggerFactory.getLogger(LocationServicesRouteBuilder.class);
	
	private Environment env;
	
	private GeoApi geoApi;
	
	private GeocodingRepository geocodingRepository;
	
	private HazelcastService hazelcastService;

	@Autowired
	public void setHazelcastService(HazelcastService hazelcastService) {
		this.hazelcastService = hazelcastService;
	}

	@Autowired
	void setGeoApi(GeoApi geoApi){
		this.geoApi =geoApi;
	}
	
	@Autowired
	void setEnvironment(Environment e){
		logger.info(" LocationServicesRouteBuilder  #### env:"+e.getProperty("broker.url"));
		env = e;
	}
	
	@Autowired
	public void setGeocodingRepository(GeocodingRepository geocodingRepository) {
		this.geocodingRepository = geocodingRepository;
		logger.info("#########################  mongo db collection initialization  ####################");
		this.geocodingRepository.createGeocodingCollection();
	}

	@Override
    public void configure() throws Exception {

		
		//env = Tool.prop();
		logger.info("swagger.host:"+env.getProperty("swagger.host"));
		onException(JsonParseException.class)
	    .handled(true)
	    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
	    .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
	    .setBody().constant("Invalid json data");
		
        // configure we want to use servlet as the component for the rest DSL
        // and we enable json binding mode //netty4-http
        restConfiguration().component("servlet").bindingMode(RestBindingMode.json)
        	.host(env.getProperty("swagger.host"))
            .dataFormatProperty("prettyPrint", "true")
            .scheme(env.getProperty("swagger.scheme"))
            .contextPath("Location")
            .port(env.getProperty("server.port"))
            .apiContextPath("/api-doc")
            .apiProperty("api.title", env.getProperty("swagger.title"))
            .apiProperty("api.description", env.getProperty("swagger.description"))
            .apiProperty("api.termsOfServiceUrl", env.getProperty("swagger.termsOfServiceUrl"))
            .apiProperty("api.license", env.getProperty("swagger.license"))
            .apiProperty("api.licenseUrl", env.getProperty("swagger.licenseUrl"))
            .apiProperty("api.version", env.getProperty("swagger.version"))
             .apiProperty("cors", env.getProperty("swagger.cors"));

        
        rest("/api").description("Location Services")
            .consumes("application/json").produces("application/json")

		.get("/ping")
		.outType(Geocoding.class)
		.to("direct:ping")
		
		
		//https://host:port/locations/bds/{bdsid}/driver/{driverid}?from=2016-09-18T00:00:00Z&to=2016-09-18T14:39:00Z
		.get("/location/latitude/{latitude}/longitude/{longitude}")
		.param().name("latitude").type(path).description("The  latitude ").dataType("string").endParam()
		.param().name("longitude").type(path).description("The  longitude ").dataType("string").endParam()
		.outType(Geocoding.class)
		.to("direct:location")

		.get("/mongoLocation/latitude/{latitude}/longitude/{longitude}")
		.param().name("latitude").type(path).description("The  latitude ").dataType("string").endParam()
		.param().name("longitude").type(path).description("The  longitude ").dataType("string").endParam()
		.outType(Geocodings.class)
		.to("direct:mongoLocation")
	
		.get("/listAllFromCache")
		.outType(Geocodings.class)
		.to("direct:listCache")
		
		.post("/savetoMongoDB")
		.description("Save a list of Geocodings to MongoDB NOSQL")
		//.typeList(Geocodings.class)
		.outType(Response.class)
		.type(Geocodings.class)
		.to("direct:saveGeocoding")
        
		.post("/savetoCache")
		.description("Save a list of Geocodings to hazelcast cluster")
		//.typeList(Geocodings.class)
		.outType(Response.class)
		.type(Geocodings.class)
		.to("direct:saveGeocodingToCache") ;      
        
        from("direct:ping")
        .id("ping")
        .routeId("ping")
        .process(exchange -> {
        	String address = geoApi.getAddress(33.969601,-84.100033);
        	Geocoding geocoding = new Geocoding();
        	geocoding.setLatitude("33.969601");
        	geocoding.setLongitude("-84.100033");
        	geocoding.setAddress(address);
			exchange.getIn().setBody(geocoding);
		});
		
        from("direct:listCache")
        .id("listCache")
        .routeId("listCache")
        .process(exchange -> {
        	String address = geoApi.getAddress(33.969601,-84.100033);
        	Geocodings geocodings = new Geocodings();
        	List<Geocoding> list = new ArrayList<Geocoding>(hazelcastService.listAllFromCache());
        	geocodings.setList(list);
			exchange.getIn().setBody(geocodings);
		});  
        
        
		from("direct:mongoLocation")
		.id("mongoLocation")
		.routeId("mongoLocation")
    	.choice()
    	.when().simple("${bean:validator?method=isLatitudeAndLongitudeRight}")
		.process(exchange -> {
			logger.info(" ############  EXCHANGE:"+exchange);
			String longitude = (String)exchange.getIn().getHeader("longitude",String.class);
			String latitude = (String)exchange.getIn().getHeader("latitude",String.class);
			//String address = geoApi.getAddress(new Double(latitude),new Double(longitude));
			Geocoding geocoding = new Geocoding();
			geocoding.setLatitude(latitude);
			geocoding.setLongitude(longitude);
        	//geocoding.setAddress(address);
			Geocodings geocodings = geocodingRepository.getGeocodings(geocoding);
			exchange.getIn().setBody(geocodings);
		}).otherwise()
    	.bean(new ErrorService(), "locationError");	 
 
		from("direct:location")
		.id("location")
		.routeId("location")
    	.choice()
    	.when().simple("${bean:validator?method=isLatitudeAndLongitudeRight}")
		.process(exchange -> {
			logger.info(" ############  EXCHANGE:"+exchange);
			String longitude = (String)exchange.getIn().getHeader("longitude",String.class);
			String latitude = (String)exchange.getIn().getHeader("latitude",String.class);
			String address = geoApi.getAddress(new Double(latitude),new Double(longitude));
			Geocoding geocoding = new Geocoding();
			geocoding.setLatitude(latitude);
			geocoding.setLongitude(longitude);
        	geocoding.setAddress(address);
			exchange.getIn().setBody(geocoding);
		}).otherwise()
    	.bean(new ErrorService(), "locationError");	 
		
		
		from("direct:saveGeocoding")
		.id("saveGeocoding")
		.routeId("saveGeocoding")
    	.choice()
    	.when().simple("${bean:validator?method=areLatitudeAndLongitudeRight}")
		.process(exchange -> {
			logger.info(" insert ############  EXCHANGE:"+exchange);
			Geocodings geocodings = (Geocodings)exchange.getIn().getBody(Geocodings.class);
			
			for(Geocoding geocoding: geocodings.getList()){//creating UUID
				geocoding.setGeocodingId(Tool.unique());
			}
			geocodingRepository.insertGeocoding(geocodings.getList());
			logger.info("  ############ INSERT TO MONGODB COMPLETED");
			Response response = new Response();
			response.setRequest(geocodings);
			response.setMsg("Message successfully saved to mongodb!");
			response.setStatus("OK");
			exchange.getIn().setBody(response);
		});		
		
		from("direct:saveGeocodingToCache")
		.id("saveGeocodingToCache")
		.routeId("saveGeocodingToCache")
    	.choice()
    	.when().simple("${bean:validator?method=areLatitudeAndLongitudeRight}")
		.process(exchange -> {
			logger.info(" insert ############  EXCHANGE:"+exchange);
			Geocodings geocodings = (Geocodings)exchange.getIn().getBody(Geocodings.class);
			
			for(Geocoding geocoding: geocodings.getList()){//creating UUID
				geocoding.setGeocodingId(Tool.unique());
				hazelcastService.insertToCache(geocoding);
			}
			logger.info("  ############ INSERT TO CACHE COMPLETED");
			Response response = new Response();
			response.setRequest(geocodings);
			response.setMsg("Message successfully saved to cache!");
			response.setStatus("OK");
			exchange.getIn().setBody(response);
		});		
		
		
		
    }
	


}
