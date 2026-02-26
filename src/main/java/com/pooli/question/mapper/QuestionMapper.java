package com.pooli.question.mapper;

import com.pooli.question.domain.entity.QuestionCategory;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface QuestionMapper {

    List<QuestionCategory> findAllActiveCategories();

}
