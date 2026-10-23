package com.hmdp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.api.client.UserClient;
import com.hmall.api.dto.UserProfileDTO;
import com.hmall.common.utils.UserContext;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/blog-comments")
@RequiredArgsConstructor
public class BlogCommentsController {
    private final IBlogCommentsService commentsService;
    private final IBlogService blogService;
    private final UserClient userClient;

    @GetMapping("/{blogId}")
    public Result list(@PathVariable Long blogId,
                       @RequestParam(value = "current", defaultValue = "1") long current) {
        if (blogService.getById(blogId) == null) return Result.fail("笔记不存在");
        Page<BlogComments> page = commentsService.lambdaQuery()
                .eq(BlogComments::getBlogId, blogId)
                .eq(BlogComments::getStatus, false)
                .orderByDesc(BlogComments::getCreateTime)
                .page(new Page<>(Math.max(1, current), 20));
        List<CommentView> views = new ArrayList<>();
        for (BlogComments comment : page.getRecords()) {
            UserProfileDTO profile = userClient.getProfile(comment.getUserId());
            CommentView view = new CommentView();
            view.setId(comment.getId());
            view.setUserId(comment.getUserId());
            view.setContent(comment.getContent());
            view.setCreateTime(comment.getCreateTime());
            view.setNickName(profile == null ? "用户" : profile.getNickName());
            view.setIcon(profile == null ? null : profile.getIcon());
            views.add(view);
        }
        return Result.ok(views);
    }

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result add(@RequestBody BlogComments input) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.fail("请先登录");
        if (input.getBlogId() == null || blogService.getById(input.getBlogId()) == null) {
            return Result.fail("笔记不存在");
        }
        String content = input.getContent() == null ? "" : input.getContent().trim();
        if (content.isEmpty() || content.length() > 255) return Result.fail("评论长度应为 1 到 255 字");
        BlogComments comment = new BlogComments()
                .setBlogId(input.getBlogId()).setUserId(userId)
                .setParentId(0L).setAnswerId(0L).setContent(content)
                .setLiked(0).setStatus(false);
        commentsService.save(comment);
        blogService.update().setSql("comments = IFNULL(comments, 0) + 1")
                .eq("id", input.getBlogId()).update();
        return Result.ok(comment.getId());
    }

    @Data
    private static class CommentView {
        private Long id;
        private Long userId;
        private String nickName;
        private String icon;
        private String content;
        private LocalDateTime createTime;
    }
}
