package com.hmdp.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmall.api.client.UserClient;
import com.hmall.api.dto.UserProfileDTO;
import com.hmall.common.utils.UserContext;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.service.IFollowService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource private IFollowService followService;
    @Resource private StringRedisTemplate redis;
    @Resource private UserClient userClient;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable Long id, @PathVariable Boolean isFollow) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.fail("请先登录");
        if (userId.equals(id)) return Result.fail("不能关注自己");
        QueryWrapper<Follow> query = new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id);
        if (Boolean.TRUE.equals(isFollow)) {
            if (followService.count(query) == 0) {
                Follow row = new Follow().setUserId(userId).setFollowUserId(id);
                followService.save(row);
            }
            redis.opsForSet().add(key(userId), id.toString());
        } else {
            followService.remove(query);
            redis.opsForSet().remove(key(userId), id.toString());
        }
        return Result.ok();
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable Long id) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.ok(false);
        Boolean cached = redis.opsForSet().isMember(key(userId), id.toString());
        if (Boolean.TRUE.equals(cached)) return Result.ok(true);
        boolean followed = followService.count(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id)) > 0;
        if (followed) redis.opsForSet().add(key(userId), id.toString());
        return Result.ok(followed);
    }

    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable Long id) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.fail("请先登录");
        List<Long> mine = followedIds(userId);
        List<Long> theirs = followedIds(id);
        Set<Long> common = mine.stream().filter(theirs::contains).collect(Collectors.toSet());
        List<UserProfileDTO> profiles = new ArrayList<>();
        for (Long followId : common) {
            UserProfileDTO profile = userClient.getProfile(followId);
            if (profile != null) profiles.add(profile);
        }
        return Result.ok(profiles);
    }

    private List<Long> followedIds(Long userId) {
        Set<String> cached = redis.opsForSet().members(key(userId));
        if (cached != null && !cached.isEmpty()) {
            return cached.stream().map(Long::valueOf).collect(Collectors.toList());
        }
        List<Long> ids = followService.list(new QueryWrapper<Follow>().eq("user_id", userId))
                .stream().map(Follow::getFollowUserId).collect(Collectors.toList());
        if (!ids.isEmpty()) redis.opsForSet().add(key(userId), ids.stream().map(String::valueOf).toArray(String[]::new));
        return ids;
    }

    private String key(Long userId) { return "follow:" + userId; }
}
