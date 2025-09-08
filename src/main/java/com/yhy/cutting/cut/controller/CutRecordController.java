package com.yhy.cutting.cut.controller;

import com.yhy.cutting.cut.repository.CutRecordRepository;
import com.yhy.cutting.cut.vo.CutRecord;
import com.yhy.cutting.cut.vo.R;
import com.yhy.cutting.cut.vo.RecordRequest;
import com.yhy.cutting.user.vo.User;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


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

    @GetMapping(value = "list")
    public R<Page<CutRecord>> queryRecords( LocalDateTime startTime,  LocalDateTime endTime, String name,String type,int current, int size) {

        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Specification<CutRecord> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if(!user.isSuperAdmin()){
                predicates.add(cb.equal(root.get("userId"), user.getId()));
            }

            // 时间范围
            if (startTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createTime"), startTime));
            }
            if (endTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createTime"), endTime));
            }

            // 名称模糊匹配
            if (name != null && !name.isEmpty()) {
                predicates.add(cb.like(root.get("name"), "%" + name + "%"));
            }

            // 类型精确匹配
            if (type != null && !type.isEmpty()) {
                predicates.add(cb.equal(root.get("type"), type));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return R.ok(repository.findAll(specification, PageRequest.of(current-1, size, Sort.Direction.DESC, "createTime")));
    }

}
