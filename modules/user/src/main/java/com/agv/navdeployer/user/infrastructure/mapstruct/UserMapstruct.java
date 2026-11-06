package com.agv.navdeployer.user.infrastructure.mapstruct;

import com.agv.navdeployer.user.application.dto.UserDTO;
import com.agv.navdeployer.user.domain.User;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapstruct {

    UserDTO toDTO(User user);

    List<UserDTO> toDTOList(List<User> users);
}
