package file.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;

import file.entity.AttachmentFile;
import file.repository.AttachmentFileRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class S3Service {
	
	private final AmazonS3 amazonS3;
	private final AttachmentFileRepository fileRepository;
	
    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;
    
    private final String DIR_NAME = "s3_data";
    
    
    // 파일 업로드
	@Transactional
	public void uploadS3File(MultipartFile file) {
		
		String savePath = "/Users/yujin/cloudboot-sts/" + DIR_NAME;
		String originalFileName = file.getOriginalFilename();
		String fileName = UUID.randomUUID().toString() + "_" + originalFileName;
		long size = file.getSize();
		
		// DB 저장
		AttachmentFile attachmentFile = AttachmentFile.builder()
				.attachmentFileName(fileName)
				.attachmentOriginalFileName(originalFileName)
				.filePath(savePath)
				.attachmentFileSize(size)
				.build();
		
		final Long fileNo = fileRepository.save(attachmentFile).getAttachmentFileNo();
		
		
		// 로컬에 임시 파일 저장 -> S3 전송 및 저장 (putObject)
		if(fileNo == null) {
			throw new RuntimeException("파일 저장 오류 발생");
		}
		
		final File s3File = new File(attachmentFile.getFilePath() + "/" + fileName);
		
		try {
			// 1. 임시 파일 저장
			file.transferTo(s3File);
			String key = DIR_NAME + "/" + fileName; 
			
			// 2. s3 파일 업로드
			amazonS3.putObject(bucketName, key, s3File);
			
		} catch (IllegalStateException | IOException e) {
			throw new RuntimeException("s3 파일 저장 오류 발생", e);
		} finally {
	        // s3 업로드에 성공하거나 실패해도 임시파일 삭제 시도
	        if (s3File.exists() && !s3File.delete()) {
	            System.err.println("임시 파일 삭제 실패: " + s3File.getAbsolutePath());
	        }
		}
		
		
	}
	
	// 파일 다운로드
	@Transactional
	public ResponseEntity<Resource> downloadS3File(long fileNo){
		
		// DB에서 파일 검색
		final AttachmentFile file = fileRepository.findById(fileNo)
				.orElseThrow(() ->  new NoSuchElementException("파일이 존재하지 않습니다."));
		
		String key = DIR_NAME + "/" + file.getAttachmentFileName(); 
		
		// S3의 파일 가져오기 (getObject) -> 전달
		final S3Object s3File = amazonS3.getObject(bucketName, key);
		
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		headers.setContentDisposition(ContentDisposition
				.attachment()
				.filename(file.getAttachmentOriginalFileName(), StandardCharsets.UTF_8)
				.build());
		
		
		final Resource body = new InputStreamResource(s3File.getObjectContent());
		
		// 브라우저에서 요청 시, 파일 다운로드 	
		return new ResponseEntity<Resource>(body, headers, HttpStatus.OK);
				
	}
	
}