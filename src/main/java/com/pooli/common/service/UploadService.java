package com.pooli.common.service;


import com.pooli.common.dto.request.PresignedUrlReqDto;
import com.pooli.common.dto.request.UploadFileReqDto;
import com.pooli.common.dto.response.PresignedUrlResDto;
import com.pooli.common.dto.response.UploadFileResDto;
import com.pooli.common.enums.FileDomain;

public interface UploadService {

    PresignedUrlResDto generatePresignedUrls(PresignedUrlReqDto request);
    UploadFileResDto createPresignedUrl(UploadFileReqDto file, FileDomain  domain);
    String generateKey(UploadFileReqDto file, FileDomain domain);
    String generateGetPresignedUrl(String key);

}
