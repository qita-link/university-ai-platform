package com.campus.platform.ai.tool;

import com.campus.platform.common.api.PageResult;
import com.campus.platform.forum.dto.ForumCommentCreateRequest;
import com.campus.platform.forum.dto.ForumCommentView;
import com.campus.platform.forum.dto.ForumPostCreateRequest;
import com.campus.platform.forum.dto.ForumPostDetailView;
import com.campus.platform.forum.dto.ForumPostQuery;
import com.campus.platform.forum.dto.ForumPostSummaryView;
import com.campus.platform.forum.dto.ForumPostUpdateRequest;
import com.campus.platform.forum.entity.ForumCategory;
import com.campus.platform.forum.service.ForumCategoryService;
import com.campus.platform.forum.service.ForumCommentService;
import com.campus.platform.forum.service.ForumPostService;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.campus.platform.ai.tool.ToolParameter.optional;
import static com.campus.platform.ai.tool.ToolParameter.required;

/**
 * 论坛工具：检索（只读）与发帖/改帖/评论/删帖（写操作，需确认）。
 * <p>
 * 帖子可见性、作者归属、版主权限全部由论坛 Service 判断；工具不复制这些规则。
 */
@Component
public class ForumToolProvider implements AiToolProvider {

    private static final int EXCERPT_PREVIEW_CHARS = 60;

    private final ForumPostService postService;
    private final ForumCommentService commentService;
    private final ForumCategoryService categoryService;

    public ForumToolProvider(ForumPostService postService, ForumCommentService commentService,
                             ForumCategoryService categoryService) {
        this.postService = postService;
        this.commentService = commentService;
        this.categoryService = categoryService;
    }

    @Override
    public List<AiTool> tools() {
        return List.of(searchPosts(), createPost(), updatePost(), createComment(), deletePost());
    }

    private AiTool searchPosts() {
        return SimpleAiTool.readOnly("searchForumPosts", "检索论坛帖子",
                "按关键词/分类分页检索论坛帖子（标题、摘要、作者、评论数）。只返回当前用户可见的已发布帖子。只读。",
                List.of(),
                List.of(optional("keyword", "string", "标题或正文关键词"),
                        optional("categoryId", "integer", "分类 ID"),
                        optional("page", "integer", "页码，从 1 开始"),
                        optional("size", "integer", "每页条数 1~50，默认 10")),
                (user, args) -> {
                    ForumPostQuery query = new ForumPostQuery();
                    query.setKeyword(args.optionalString("keyword", 100));
                    query.setCategoryId(args.optionalLong("categoryId"));
                    query.setPage(args.optionalInt("page", 1, 1, 100000));
                    query.setSize(args.optionalInt("size", 10, 1, 50));
                    PageResult<ForumPostSummaryView> page = postService.list(user, query);
                    return new ToolResult(page, "返回 " + page.items().size() + " 条，共 " + page.total() + " 条");
                });
    }

    private AiTool createPost() {
        return SimpleAiTool.write("createForumPost", "发布论坛帖子",
                "在指定分类下发布帖子。需要 forum:post:write。写操作，需用户确认后才会真正发布。",
                List.of(Permissions.FORUM_POST_WRITE),
                List.of(required("categoryId", "integer", "分类 ID"),
                        required("title", "string", "标题，最多 200 字"),
                        required("content", "string", "正文")),
                (user, args) -> {
                    ForumCategory category = categoryService.requireActive(args.requireLong("categoryId"));
                    String content = args.requireString("content", 50000);
                    return new ToolPreview("将在分类「" + category.getName() + "」发布帖子《"
                            + args.requireString("title", 200) + "》，正文 " + content.length() + " 字："
                            + excerpt(content));
                },
                (user, args) -> {
                    ForumPostDetailView post = postService.create(user, new ForumPostCreateRequest(
                            args.requireLong("categoryId"),
                            args.requireString("title", 200),
                            args.requireString("content", 50000)));
                    return new ToolResult(post, "已发布帖子《" + post.title() + "》（" + post.id() + "）");
                });
    }

    private AiTool updatePost() {
        return SimpleAiTool.write("updateForumPost", "修改论坛帖子",
                "整体修改本人帖子（或版主修改任意帖子）的分类/标题/正文，需回传当前 version。写操作，需用户确认。",
                List.of(Permissions.FORUM_POST_WRITE, Permissions.FORUM_MODERATE),
                List.of(required("postId", "integer", "帖子 ID"),
                        required("categoryId", "integer", "分类 ID"),
                        required("title", "string", "标题"),
                        required("content", "string", "正文"),
                        required("version", "integer", "当前版本号")),
                (user, args) -> {
                    ForumPostDetailView post = postService.get(user, args.requireLong("postId"));
                    return new ToolPreview("将修改帖子《" + post.title() + "》（" + post.id() + "，v" + post.version()
                            + "）：标题改为「" + args.requireString("title", 200) + "」，正文 "
                            + args.requireString("content", 50000).length() + " 字");
                },
                (user, args) -> {
                    ForumPostDetailView post = postService.update(user, args.requireLong("postId"),
                            new ForumPostUpdateRequest(
                                    args.requireLong("categoryId"),
                                    args.requireString("title", 200),
                                    args.requireString("content", 50000),
                                    args.requireInt("version", 0, Integer.MAX_VALUE)));
                    return new ToolResult(post, "已修改帖子《" + post.title() + "》（v" + post.version() + "）");
                });
    }

    private AiTool createComment() {
        return SimpleAiTool.write("createForumComment", "发表评论",
                "在帖子下发表顶级评论或回复某条评论（回复回复仍归入同一线程）。需要 forum:comment:write。写操作，需用户确认。",
                List.of(Permissions.FORUM_COMMENT_WRITE),
                List.of(required("postId", "integer", "帖子 ID"),
                        required("content", "string", "评论内容，最多 2000 字"),
                        optional("parentId", "integer", "被回复的评论 ID；为空表示顶级评论")),
                (user, args) -> {
                    ForumPostDetailView post = postService.get(user, args.requireLong("postId"));
                    Long parentId = args.optionalLong("parentId");
                    return new ToolPreview("将在帖子《" + post.title() + "》下"
                            + (parentId == null ? "发表评论：" : "回复评论 #" + parentId + "：")
                            + args.requireString("content", 2000));
                },
                (user, args) -> {
                    ForumCommentView comment = commentService.create(user, args.requireLong("postId"),
                            new ForumCommentCreateRequest(
                                    args.requireString("content", 2000),
                                    args.optionalLong("parentId")));
                    return new ToolResult(comment, "已发表评论（" + comment.id() + "）");
                });
    }

    private AiTool deletePost() {
        return SimpleAiTool.write("deleteForumPost", "删除论坛帖子",
                "软删除本人帖子（版主可删任意帖子）。删除后帖子对所有人不可见，评论一并不可见。写操作，需用户确认。",
                List.of(Permissions.FORUM_POST_WRITE, Permissions.FORUM_MODERATE),
                List.of(required("postId", "integer", "帖子 ID")),
                (user, args) -> {
                    ForumPostDetailView post = postService.get(user, args.requireLong("postId"));
                    return new ToolPreview("将删除帖子《" + post.title() + "》（" + post.id() + "，作者 "
                            + post.authorName() + "，当前 " + post.commentCount()
                            + " 条评论）；删除后不可见，内容保留用于追溯");
                },
                (user, args) -> {
                    long postId = args.requireLong("postId");
                    ForumPostDetailView post = postService.get(user, postId);
                    postService.delete(user, postId);
                    return new ToolResult(null, "已删除帖子《" + post.title() + "》");
                });
    }

    private static String excerpt(String content) {
        String text = content.replaceAll("\\s+", " ").trim();
        return text.length() <= EXCERPT_PREVIEW_CHARS ? text : text.substring(0, EXCERPT_PREVIEW_CHARS) + "…";
    }
}
