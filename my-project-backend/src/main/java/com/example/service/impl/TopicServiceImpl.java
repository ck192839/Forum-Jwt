package com.example.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.search.HybridTopicSearchService;
import com.example.search.RankedTopic;
import com.example.search.TopicSearchHit;
import com.example.search.index.TopicIndexEventPublisher;
import com.example.entity.dto.*;
import com.example.entity.vo.request.AddCommentVO;
import com.example.entity.vo.request.TopicCreateVO;
import com.example.entity.vo.request.TopicTypeCreateVO;
import com.example.entity.vo.request.TopicUpdateVO;
import com.example.entity.vo.response.*;
import com.example.mapper.*;
import com.example.service.NotificationService;
import com.example.service.TopicService;
import com.example.utils.CacheUtils;
import com.example.utils.Const;
import com.example.utils.FlowUtils;
import com.example.utils.ProhibitedUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class TopicServiceImpl extends ServiceImpl<TopicMapper, Topic> implements TopicService {
    @Resource
    TopicTypeMapper mapper;

    @Resource
    FlowUtils flowUtils;

    @Resource
    CacheUtils cacheUtils;

    @Resource
    AccountMapper accountMapper;

    @Resource
    AccountDetailsMapper accountDetailsMapper;

    @Resource
    AccountPrivacyMapper accountPrivacyMapper;

    @Resource
    StringRedisTemplate template;

    @Resource
    TopicCommentMapper commentMapper;

    @Resource
    NotificationService notificationService;

    @Resource
    ProhibitedUtils prohibitedUtils;

    @Resource
    HybridTopicSearchService hybridSearchService;

    @Resource
    TopicIndexEventPublisher topicIndexEventPublisher;

    private volatile Set<Integer> types = null;

    @PostConstruct
    private void initTypes() {// 只获取所有主题类型的id
        refreshTypes();
    }

    private void refreshTypes() {// 类型增删后整体重建校验缓存
        types = this.listTypes()
                .stream()
                .map(TopicType::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public List<TopicType> listTypes() {// 取出所有主题类型
        return mapper.selectList(null);
    }

    @Override
    public void updateTopicType(TopicTypeVO vo) {
        TopicType topicType = mapper.selectById(vo.getId());
        BeanUtils.copyProperties(vo, topicType);
        mapper.updateById(topicType);
    }

    @Override
    @Transactional
    public void deleteTopicType(int id) {
        TopicType type = mapper.selectById(id);
        if (mapper.deleteById(id) > 0) {
            List<Topic> list = baseMapper.selectList(Wrappers.<Topic>query().eq("type", type.getId()));
            list.forEach(topic -> deleteTopic(topic.getId()));
        }
        // 回滚时不能刷新缓存，否则被删类型在重启前无法继续用于校验
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshTypes();
                }
            });
        } else {
            refreshTypes();
        }
    }

    @Override
    public void createTopicType(TopicTypeCreateVO vo) {
        TopicType type = new TopicType();
        BeanUtils.copyProperties(vo, type);
        mapper.insert(type);
        refreshTypes();
    }

    @Override
    @Transactional
    public void changeTopicType(int tid, int type) {
        if (baseMapper.update(null, Wrappers.<Topic>update()
                .eq("id", tid)
                .set("type", type)) > 0) {
            cacheUtils.deleteCachePatternAfterCommit(Const.FORUM_TOPIC_PREVIEW_CACHE + "*");
            topicIndexEventPublisher.upsert(tid);
        }
    }

    @Override
    @Transactional
    public String createTopic(int uid, TopicCreateVO vo) {// 创建帖子 //todo 自建了title违禁词检查
        if (!textLimitCheck(vo.getContent(), 20000))
            return "内容过多，发文失败";
        if ((!types.contains(vo.getType())))
            return "主题类型非法";
        String key = Const.FORUM_TOPIC_CREATE_COUNTER + uid;
        if (!flowUtils.limitPeriodCounterCheck(key, 3, 3600))
            return "发文频繁，稍后再试！";
        if (prohibitedUtils.containsProhibitedWord(vo.getTitle())
                || prohibitedUtils.containsProhibitedWord(vo.getContent()))
            return "内容包含违禁词，发文失败";
        Topic topic = new Topic();
        BeanUtils.copyProperties(vo, topic);
        topic.setUid(uid);
        topic.setTime(new Date());
        topic.setContent(vo.getContent().toJSONString());
        topic.createIntro();
        if (this.save(topic)) {
            cacheUtils.deleteCachePatternAfterCommit(Const.FORUM_TOPIC_PREVIEW_CACHE + "*");// 删除所有缓存
            topicIndexEventPublisher.upsert(topic.getId());
            return null;
        } else {
            return "内部错误,联系管理员";
        }

    }

    @Override
    @Transactional
    public String updateTopic(int uid, TopicUpdateVO vo) {

        if (!textLimitCheck(vo.getContent(), 20000))
            return "内容过多，发文失败";
        if ((!types.contains(vo.getType())))
            return "主题类型非法";
        if (prohibitedUtils.containsProhibitedWord(vo.getContent()))
            return "内容包含违禁词，更新失败";
        int result = baseMapper.update(null, Wrappers.<Topic>update()
                .eq("uid", uid)
                .eq("id", vo.getId())
                .eq("locked", 0)
                .set("title", vo.getTitle())
                .set("content", vo.getContent().toJSONString())
                .set("type", vo.getType())
                .set("intro", Topic.recreateIntro(vo.getContent())));
        if (result == 1) {// todo 自建更新删缓存
            cacheUtils.deleteCachePatternAfterCommit(Const.FORUM_TOPIC_PREVIEW_CACHE + "*");// 删除所有缓存
            cacheUtils.deleteCacheAfterCommit(Const.FORUM_TOPIC_TOP_CACHE);
            topicIndexEventPublisher.upsert(vo.getId());
            return null;
        }
        return "文章被锁定，更新失败";
    }

    @Override
    public String createComment(int uid, AddCommentVO vo) {// 发表评论//todo 学习
        if (!textLimitCheck(JSONObject.parseObject(vo.getContent()), 2000))
            return "内容过多，评论失败";
        String key = Const.FORUM_TOPIC_COMMENT_COUNTER + uid;
        if (!flowUtils.limitPeriodCounterCheck(key, 3, 3600))
            return "评论频繁，稍后再试！";
        if (prohibitedUtils.containsProhibitedWord(vo.getContent()))
            return "内容包含违禁词，评论失败";
        TopicComment comment = new TopicComment();
        BeanUtils.copyProperties(vo, comment);
        comment.setUid(uid);
        comment.setTime(new Date());
        commentMapper.insert(comment);
        Topic topic = baseMapper.selectById(vo.getTid());
        Account account = accountMapper.selectById(uid);
        if (vo.getQuote() > 0) {// 回复评论
            TopicComment com = commentMapper.selectById(vo.getQuote());// 获取被回复的评论
            if (!Objects.equals(account.getId(), com.getUid())) {// 如果不是自己回复
                notificationService.addNotification(
                        com.getUid(),
                        "您有新的帖子评论回复",
                        account.getUsername() + " 回复了你发表的评论，快去看看吧！",
                        "success", "/index/topic-detail/" + com.getTid());
            }
        } else if (!Objects.equals(account.getId(), topic.getUid())) {// 回复帖子并且不是自己回复
            notificationService.addNotification(
                    topic.getUid(),
                    "您有新的帖子回复",
                    account.getUsername() + " 回复了你发表主题: " + topic.getTitle() + "，快去看看吧！",
                    "success", "/index/topic-detail/" + topic.getId());
        }
        return null;
    }

    @Override
    public List<CommentVO> comments(int tid, int pageNumber) {// todo 学习
        Page<TopicComment> page = new Page<>(pageNumber, 10);
        commentMapper.selectPage(page, Wrappers.<TopicComment>query().eq("tid", tid).orderByDesc("time"));
        return page.getRecords().stream()
                .map(dto -> {
                    CommentVO vo = new CommentVO();
                    BeanUtils.copyProperties(dto, vo);
                    if (dto.getQuote() > 0) {
                        TopicComment comment = commentMapper.selectOne(
                                Wrappers.<TopicComment>query()
                                        .eq("id", dto.getQuote())
                                        .orderByDesc("time"));
                        if (comment != null) {
                            JSONObject object = JSONObject.parseObject(comment.getContent());
                            StringBuilder builder = new StringBuilder();
                            this.shortContent(object.getJSONArray("ops"), builder, ignore -> {
                            });
                            vo.setQuote(builder.toString());
                        } else {
                            vo.setQuote("此评论已被删除");
                        }
                    }
                    CommentVO.User user = new CommentVO.User();
                    this.fillUserDetailByPrivacy(user, dto.getUid());
                    vo.setUser(user);
                    return vo;
                }).toList();
    }

    @Override
    public void deleteComment(int id, int uid) {// 评论主人删除评论
        commentMapper.delete(Wrappers.<TopicComment>query()
                .eq("id", id)
                .eq("uid", uid));
    }

    @Override
    @Transactional
    public void deleteTopic(int id) {// 管理端删除帖子
        int result = baseMapper.deleteById(id);
        cacheUtils.deleteCachePatternAfterCommit(Const.FORUM_TOPIC_PREVIEW_CACHE + "*");
        cacheUtils.deleteCacheAfterCommit(Const.FORUM_TOPIC_TOP_CACHE);
        baseMapper.deleteTopicCollect(id);
        baseMapper.deleteTopicLike(id);
        if (result > 0)
            topicIndexEventPublisher.delete(id);
    }

    @Override
    @Transactional
    public void deleteTopic(int tid, int uid) { // 用户删除自己的帖子
        int result = baseMapper.delete(Wrappers.<Topic>query()
                .eq("id", tid)
                .eq("uid", uid));
        if (result > 0) {
            cacheUtils.deleteCachePatternAfterCommit(Const.FORUM_TOPIC_PREVIEW_CACHE + "*");
            cacheUtils.deleteCacheAfterCommit(Const.FORUM_TOPIC_TOP_CACHE);
            baseMapper.deleteTopicCollect(tid);
            baseMapper.deleteTopicLike(tid);
            topicIndexEventPublisher.delete(tid);
        }
    }

    @Override
    @Transactional
    public void setTopicTop(int tid, boolean top) {// 设置帖子置顶
        int result = baseMapper.update(null, Wrappers.<Topic>update()
                .eq("id", tid)
                .set("top", top));
        if (result > 0)
            cacheUtils.deleteCacheAfterCommit(Const.FORUM_TOPIC_TOP_CACHE);
    }

    @Override
    public void setTopicLocked(int tid, boolean locked) {// 设置帖子锁定
        baseMapper.update(null, Wrappers.<Topic>update()
                .eq("id", tid)
                .set("locked", locked));
    }

    @Override
    @Transactional
    public void setTopicInvisible(int tid, boolean invisible) {// 设置帖子是否可见设置为true表示不可见
        int result = baseMapper.update(null, Wrappers.<Topic>update()
                .eq("id", tid)
                .set("invisible", invisible));
        if (result > 0) {
            cacheUtils.deleteCachePatternAfterCommit(Const.FORUM_TOPIC_PREVIEW_CACHE + "*");
            cacheUtils.deleteCacheAfterCommit(Const.FORUM_TOPIC_TOP_CACHE);
            topicIndexEventPublisher.upsert(tid);
        }
    }

    @Override
    public List<TopicPreviewVO> listTopicCollects(int uid) {// 获取用户收藏的帖子
        return baseMapper.collectTopics(uid)
                .stream()
                .map(topic -> {
                    TopicPreviewVO vo = new TopicPreviewVO();
                    BeanUtils.copyProperties(topic, vo);
                    return vo;
                })
                .toList();
    }

    @Override
    public JSONObject listAllTopicByPage(int page, int size, String keyword) {// 管理端获取所有帖子
        Page<Topic> topicPage = baseMapper.selectPage(Page.of(page, size), Wrappers.<Topic>query()
                .select("id", "title", "uid", "type", "time", "top", "locked", "invisible")
                .like(keyword != null, "title", "%" + keyword + "%")
                .orderByDesc("time"));
        List<TopicPreviewVO> list = topicPage.getRecords().stream().map(this::resolveToPreview).toList();
        JSONObject object = new JSONObject();
        object.put("total", topicPage.getTotal());
        object.put("list", list);
        object.put("blocked", baseMapper.selectCount(Wrappers.<Topic>query().eq("invisible", 1)));
        return object;
    }

    @Override
    public List<TopicPreviewVO> listTopicByPage(int pageNumber, int type) {// 分页帖子预览
        String key = Const.FORUM_TOPIC_PREVIEW_CACHE + pageNumber + ":" + type;
        List<TopicPreviewVO> list = cacheUtils.takeListFromCache(key, TopicPreviewVO.class);
        if (list != null)
            return list;
        Page<Topic> page = new Page<>(pageNumber, 10);
        if (type == 0) {
            baseMapper.selectPage(page, Wrappers.<Topic>query()
                    .eq("invisible", 0).orderByDesc("time"));
        } else {
            baseMapper.selectPage(page, Wrappers.<Topic>query()
                    .eq("type", type)
                    .eq("invisible", 0).orderByDesc("time"));
        }
        List<Topic> topics = page.getRecords();
        list = topics.stream()
                .map(this::resolveToPreview)
                .toList();
        cacheUtils.saveListToCache(key, list, 60);// 放入缓存，过期时间60秒
        return list;
    }

    @Override
    public List<TopicTopVO> listTopTopics() {// 顶置top=1的帖子
        List<TopicTopVO> cache = cacheUtils.takeListFromCache(
                Const.FORUM_TOPIC_TOP_CACHE, TopicTopVO.class);
        if (cache != null)
            return cache;
        List<Topic> topics = baseMapper.selectList(Wrappers.<Topic>query()
                .select("id", "title", "time")
                .eq("top", 1)
                .eq("invisible", 0));
        List<TopicTopVO> list = topics.stream()
                .map(topic -> {
                    TopicTopVO vo = new TopicTopVO();
                    BeanUtils.copyProperties(topic, vo);
                    return vo;
                })
                .toList();
        cacheUtils.saveListToCache(Const.FORUM_TOPIC_TOP_CACHE, list, 300);
        return list;
    }

    @Override
    public TopicDetailVO getTopic(int tid, int uid) {// 获取帖子详情
        Topic topic = baseMapper.selectById(tid);
        if (topic == null)
            return null;
        if (topic.getInvisible() == 1 && topic.getUid() != uid)
            return null;// NOTE topic为null会报错
        TopicDetailVO vo = new TopicDetailVO();
        BeanUtils.copyProperties(topic, vo);
        TopicDetailVO.Interact interact = new TopicDetailVO.Interact(
                hasInteract(tid, uid, "like"),
                hasInteract(tid, uid, "collect"));
        vo.setInteract(interact);
        TopicDetailVO.User user = new TopicDetailVO.User();
        vo.setUser(fillUserDetailByPrivacy(user, topic.getUid()));
        vo.setComments(commentMapper.selectCount(Wrappers.<TopicComment>query().eq("tid", tid)));
        return vo;
    }

    @Override
    public void interact(Interact interact, boolean state) {// 帖子交互写入缓存，state=执行/取消
        String type = interact.getType();
        InteractType.parse(type);
        synchronized (type.intern()) {
            template.opsForHash().put(type, interact.toKey(), Boolean.toString(state));
            this.saveInteractSchedule(type);
        }
    }

    @Override
    public List<TopicVO> listTopicByUser(int uid) {// 获取用户发布的帖子
        List<Topic> topics = baseMapper.selectList(Wrappers.<Topic>query().eq("uid", uid));
        return topics.stream()
                .map(topic -> {
                    TopicVO vo = new TopicVO();
                    BeanUtils.copyProperties(topic, vo);
                    return vo;
                })
                .toList();
    }

    @Value("${search.db:false}")
    private boolean dbSearch;

    /** 站内搜索返回条数上限。 */
    private static final int SEARCH_RESULT_LIMIT = 20;

    @Override
    public List<TopicSearchVO> searchTopic(String keyword) {
        if (dbSearch) {
            // 基准测试开关：search.db=true 时走 MySQL LIKE 全表扫（同步基线），
            // 用于对比 Elasticsearch 检索收益；日常保持 false。
            return baseMapper.selectList(Wrappers.<Topic>query()
                            .select("id", "title", "intro", "type")
                            .like("title", keyword).or().like("intro", keyword)
                            .last("LIMIT 20")).stream().map(topic -> {
                TopicSearchVO vo = new TopicSearchVO();
                vo.setId(topic.getId());
                vo.setTitle(topic.getTitle());
                vo.setIntro(topic.getIntro());
                vo.setType(topic.getType());
                return vo;
            }).toList();
        }
        // 混合检索：关键词+向量双路 RRF 融合；向量路不可用时自动降级为纯关键词。
        List<RankedTopic> list = hybridSearchService.search(keyword, SEARCH_RESULT_LIMIT);
        return list.stream().map(ranked -> {
            TopicSearchHit hit = ranked.topic();
            TopicSearchVO vo = new TopicSearchVO();
            vo.setId(hit.topicId());
            vo.setTitle(hit.title());
            vo.setIntro(excerptForDisplay(hit));
            vo.setType(hit.topicTypeId());
            vo.setHighlight(hit.highlight());
            return vo;
        }).toList();
    }

    /** 无高亮片段时的回退摘要：向量路命中的是正文块（最长 2400 字符），截断到 200 字符展示。 */
    private String excerptForDisplay(TopicSearchHit hit) {
        String excerpt = hit.excerpt();
        if (excerpt == null || excerpt.length() <= 200) {
            return excerpt;
        }
        return excerpt.substring(0, 200) + "…";
    }

    private boolean hasInteract(int tid, int uid, String type) {// 判断用户是否对帖子有互动
        InteractType interactType = InteractType.parse(type);
        String key = tid + ":" + uid;
        if (template.opsForHash().hasKey(type, key)) {
            return Boolean.parseBoolean(template.opsForHash().entries(type).get(key).toString());
        }
        return baseMapper.userInteractCount(tid, uid, interactType) > 0;
    }

    private final Map<String, Boolean> state = new HashMap<>();
    ScheduledExecutorService service = Executors.newScheduledThreadPool(2);

    private void saveInteractSchedule(String type) {// 延迟批量写入数据库
        if (!state.getOrDefault(type, false)) {
            state.put(type, true);
            service.schedule(() -> {
                this.saveInteract(type);
                state.put(type, false);
            }, 3, TimeUnit.SECONDS);
        }
    }

    private void saveInteract(String type) {// 从缓存中获取帖子交互并保存到数据库
        InteractType interactType = InteractType.parse(type);
        synchronized (type.intern()) {
            List<Interact> check = new LinkedList<>();// 添加的交互
            List<Interact> uncheck = new LinkedList<>();// 取消交互
            template.opsForHash().entries(type).forEach((k, v) -> {
                if (Boolean.parseBoolean(v.toString()))// 如果v为true
                    check.add(Interact.parseInteract(k.toString(), type));
                else
                    uncheck.add(Interact.parseInteract(k.toString(), type));
            });

            if (!check.isEmpty()) {
                baseMapper.addInteract(check, interactType);
            }

            if (!uncheck.isEmpty()) {
                baseMapper.deleteInteract(uncheck, interactType);
            }
            template.delete(type);// 删除缓存
        }
    }

    private <T> T fillUserDetailByPrivacy(T target, int uid) {// 根据隐私设置展示用户详情
        AccountDetails accountDetails = accountDetailsMapper.selectById(uid);
        Account account = accountMapper.selectById(uid);
        AccountPrivacy accountPrivacy = accountPrivacyMapper.selectById(uid);
        String[] ignores = accountPrivacy.hiddenFields();
        BeanUtils.copyProperties(accountDetails, target, ignores);
        BeanUtils.copyProperties(account, target, ignores);
        return target;
    }

    private TopicPreviewVO resolveToPreview(Topic topic) {
        TopicPreviewVO vo = new TopicPreviewVO();
        BeanUtils.copyProperties(accountMapper.selectById(topic.getUid()), vo);
        BeanUtils.copyProperties(topic, vo);
        vo.setLike(baseMapper.interactCount(topic.getId(), InteractType.LIKE));
        vo.setCollect(baseMapper.interactCount(topic.getId(), InteractType.COLLECT));
        List<String> images = new ArrayList<>();
        StringBuilder previewText = new StringBuilder();
        if (topic.getContent() != null) {
            JSONArray ops = JSONObject.parseObject(topic.getContent()).getJSONArray("ops");
            this.shortContent(ops, previewText, obj -> images.add(obj.toString()));
        }
        vo.setText(previewText.length() > 300 ? previewText.substring(0, 300) : previewText.toString());
        vo.setImages(images);
        return vo;
    }

    private void shortContent(JSONArray ops, StringBuilder previewText, Consumer<Object> imagerHandler) {// 文本预览
        int imageCount = 0;
        for (Object op : ops) {
            Object insert = JSONObject.from(op).get("insert");
            if (insert instanceof String text) {
                if (previewText.length() >= 300)
                    continue;
                previewText.append(text);
            } else if (insert instanceof Map<?, ?> map) {
                if (imageCount < 4) {
                    Optional.ofNullable(map.get("image")).ifPresent(imagerHandler);
                    imageCount++;
                }
            }
        }
    }

    private boolean textLimitCheck(JSONObject object, int max) {
        if (object == null)
            return false;
        long length = 0;
        for (Object op : object.getJSONArray("ops")) {
            length += JSONObject.from(op).getString("insert").length();
            if (length > max)
                return false;
        }
        return true;
    }
}
