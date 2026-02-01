package org.teamsparta.productapi.domain.product.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.teamsparta.productapi.domain.product.dto.request.ProductImageCreateRequest;
import org.teamsparta.productapi.global.enums.ImageType;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {

    private final S3Client s3Client;

    @Value("${spring.cloud.aws.s3.bucket:product-image}")
    private String bucket;

    @Value("${spring.cloud.aws.s3.endpoint}")
    private String endpoint;

    public ProductImageCreateRequest uploadFile(MultipartFile file, ImageType type) {
        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        String storageKey = "product/"+fileName;

        try{
            // s3 업로드 요청 생성
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .contentType(file.getContentType())
                    .build();

            // 파일 스트립 업로드
            s3Client.putObject(putObjectRequest,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("Successfully uploaded file to MinIO: {}", storageKey);

            String url =  String.format("%s/%s/%s", endpoint, bucket, storageKey);

            return new ProductImageCreateRequest(storageKey, url, type, 0, false);
        } catch(IOException e){
            throw new DomainException(DomainExceptionCode.FILE_UPLOAD);
        }
    }
}
