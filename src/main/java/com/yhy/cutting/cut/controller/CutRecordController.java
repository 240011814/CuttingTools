package com.yhy.cutting.cut.controller;

import com.yhy.cutting.cut.repository.CutRecordRepository;
import com.yhy.cutting.cut.vo.CutRecord;
import com.yhy.cutting.cut.vo.R;
import com.yhy.cutting.cut.vo.RecordRequest;
import org.springframework.web.bind.annotation.*;


@RestController()
@RequestMapping(value = "api/cutRecord")
public class CutRecordController {

    private final CutRecordRepository repository;

    public CutRecordController(CutRecordRepository repository) {
        this.repository = repository;
    }

    @PostMapping(value = "add")
    public R<CutRecord> add(@RequestBody RecordRequest request) {
        CutRecord record = repository.save(new CutRecord(request));
        return R.ok(record);
    }


    @PostMapping(value = "delete/{id}")
    public R<CutRecord> delete(@PathVariable String id) {
        repository.deleteById(id);
        return R.ok();
    }

}
