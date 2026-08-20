package com.group26.Agent.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class GetCurrentTimeTool implements Tool{


    @Override
    public String name() {
        return "GetCurrentTime";
    }

    @Override
    public String description() {
        return "获取现在的时间";
    }

    @Override
    public JsonNode getParametersSchema() {
       ObjectMapper mapper = new ObjectMapper();
       ObjectNode schema = mapper.createObjectNode();
       schema.put("type", "object");
       schema.putObject("properties");
       schema.putArray("required");

       return schema;
    }

    @Override
    public String execute(String arguments){
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    }

}
