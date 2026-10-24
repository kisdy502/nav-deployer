package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 货架（shelves 元素）：footprint + layers。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShelfData(String id, String name, FootprintData footprint, List<LayerData> layers) {
}
