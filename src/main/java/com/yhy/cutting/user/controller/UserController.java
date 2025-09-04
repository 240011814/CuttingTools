package com.yhy.cutting.user.controller;

import com.yhy.cutting.cut.vo.R;
import com.yhy.cutting.user.repository.UserRepository;
import com.yhy.cutting.user.vo.User;
import com.yhy.cutting.user.vo.UserInfo;
import com.yhy.cutting.user.vo.UserRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;


@RestController
@RequestMapping(value = "api/user")
public class UserController {


    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/list")
    public R<Page<UserInfo>> userInfo(String search, int current, int size) {
        Specification<User> spec = (root, query, cb) -> {
            if (search == null || search.trim().isEmpty()) {
                return cb.conjunction();
            }
            String likePattern = "%" + search.trim() + "%";
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(root.get("userName"), likePattern));
            predicates.add(cb.like(root.get("phone"), likePattern));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
        Page<User> result = userRepository.findAll(spec, PageRequest.of(current-1, size));
        return R.ok(result.map(x -> new UserInfo(x)));
    }

    @PostMapping("/edit")
    public R<Boolean> edit(@RequestBody UserInfo request) {
        int i = userRepository.updateUserInfo(request);
        return R.ok(i>0);
    }

    @PostMapping("/resetPassword")
    public R<Boolean> resetPassword(@RequestBody UserInfo request) {
        String encode = new BCryptPasswordEncoder().encode("123456");
        int i = userRepository.updatePasswordById(encode, request.getId());
        return R.ok(i>0);
    }
}
