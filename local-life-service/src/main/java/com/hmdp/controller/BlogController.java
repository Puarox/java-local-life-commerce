package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmall.api.client.UserClient;
import com.hmall.api.dto.UserProfileDTO;
import com.hmall.common.utils.UserContext;
import com.hmdp.entity.Follow;
import com.hmdp.service.IFollowService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private UserClient userClient;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate redis;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        // 获取登录用户
        Long userId = UserContext.getUser();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        UserProfileDTO profile = userClient.getProfile(userId);
        blog.setUserId(userId);
        blog.setName(profile == null ? null : profile.getNickName());
        blog.setIcon(profile == null ? null : profile.getIcon());
        // 保存探店博文
        blogService.save(blog);
        // 返回id
        return Result.ok(blog.getId());
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.fail("请先登录");
        String key = "blog:liked:" + id;
        Double score = redis.opsForZSet().score(key, userId.toString());
        if (score == null) {
            boolean updated = blogService.update().setSql("liked = liked + 1").eq("id", id).update();
            if (!updated) return Result.fail("笔记不存在");
            redis.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        } else {
            blogService.update().setSql("liked = CASE WHEN liked > 0 THEN liked - 1 ELSE 0 END").eq("id", id).update();
            redis.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable Long id) {
        Blog blog = blogService.getById(id);
        if (blog == null) return Result.fail("笔记不存在");
        fillProfile(blog);
        Long userId = UserContext.getUser();
        blog.setIsLike(userId != null && redis.opsForZSet().score("blog:liked:" + id, userId.toString()) != null);
        return Result.ok(blog);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable Long id) {
        Set<String> ids = redis.opsForZSet().reverseRange("blog:liked:" + id, 0, 4);
        List<UserProfileDTO> profiles = new ArrayList<>();
        if (ids != null) for (String userId : ids) {
            UserProfileDTO profile = userClient.getProfile(Long.valueOf(userId));
            if (profile != null) profiles.add(profile);
        }
        return Result.ok(profiles);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        Long userId = UserContext.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", userId).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        records.forEach(this::fillProfile);
        return Result.ok(records);
    }

    @GetMapping("/of/user")
    public Result queryUserBlogs(@RequestParam("id") Long userId,
                                 @RequestParam(value = "current", defaultValue = "1") Integer current) {
        Page<Blog> page = blogService.query().eq("user_id", userId).orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        page.getRecords().forEach(this::fillProfile);
        return Result.ok(page.getRecords());
    }

    @GetMapping("/of/follow")
    public Result queryFollowBlogs(@RequestParam(value = "lastId", required = false) Long lastId,
                                   @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.fail("请先登录");
        List<Long> followedIds = followService.list(new QueryWrapper<Follow>().eq("user_id", userId))
                .stream().map(Follow::getFollowUserId).collect(java.util.stream.Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        if (followedIds.isEmpty()) {
            result.put("list", java.util.Collections.emptyList()); result.put("minTime", 0L); result.put("offset", 0);
            return Result.ok(result);
        }
        long cursor = lastId == null ? System.currentTimeMillis() + 1 : lastId;
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<Blog> feedQuery = blogService.lambdaQuery()
                .in(Blog::getUserId, followedIds).orderByDesc(Blog::getCreateTime);
        if (cursor > System.currentTimeMillis()) feedQuery.le(Blog::getCreateTime, new Date(cursor));
        else feedQuery.lt(Blog::getCreateTime, new Date(cursor));
        Page<Blog> page = feedQuery
                .page(new Page<Blog>(1, SystemConstants.MAX_PAGE_SIZE));
        page.getRecords().forEach(this::fillProfile);
        long minTime = page.getRecords().isEmpty() ? 0L : page.getRecords().get(page.getRecords().size() - 1).getCreateTime()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        result.put("list", page.getRecords()); result.put("minTime", minTime); result.put("offset", offset == null ? 0 : offset);
        return Result.ok(result);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            fillProfile(blog);
        });
        return Result.ok(records);
    }

    private void fillProfile(Blog blog) {
        UserProfileDTO profile = userClient.getProfile(blog.getUserId());
        if (profile != null) { blog.setName(profile.getNickName()); blog.setIcon(profile.getIcon()); }
        Long currentUser = UserContext.getUser();
        blog.setIsLike(currentUser != null && redis.opsForZSet().score("blog:liked:" + blog.getId(), currentUser.toString()) != null);
    }
}
