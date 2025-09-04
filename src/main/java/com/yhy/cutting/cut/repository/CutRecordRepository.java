package com.yhy.cutting.cut.repository;

import com.yhy.cutting.cut.vo.CutRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CutRecordRepository  extends JpaRepository<CutRecord, String>, JpaSpecificationExecutor<CutRecord> {
}
