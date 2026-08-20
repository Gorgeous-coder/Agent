package com.group26.Agent.service.tools;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {
    String name();

    String description();

    JsonNode getParametersSchema();

    String execute(String arguments);
}
