package com.travel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelPlace {
    private String name;
    private String type;
    private double longitude;
    private double latitude;
    private String duration;
    private String description;
}
