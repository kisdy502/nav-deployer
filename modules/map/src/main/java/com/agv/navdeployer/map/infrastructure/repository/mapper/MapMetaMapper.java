package com.agv.navdeployer.map.infrastructure.repository.mapper;

import com.agv.navdeployer.map.domain.MapMeta;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MapMetaMapper extends BaseMapper<MapMeta> {
}
