package net.wendal.nutzbook.yvr.service;

import static net.wendal.nutzbook.core.bean.CResult._fail;
import static net.wendal.nutzbook.core.bean.CResult._ok;
import static org.nutz.integration.jedis.RedisInterceptor.jedis;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpSession;

import org.apache.commons.lang.StringEscapeUtils;
import org.nutz.aop.interceptor.async.Async;
import org.nutz.dao.Cnd;
import org.nutz.dao.Dao;
import org.nutz.dao.Sqls;
import org.nutz.dao.pager.Pager;
import org.nutz.dao.sql.Sql;
import org.nutz.integration.jedis.pubsub.PubSub;
import org.nutz.integration.jedis.pubsub.PubSubService;
import org.nutz.ioc.aop.Aop;
import org.nutz.ioc.impl.PropertiesProxy;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.json.Json;
import org.nutz.json.JsonFormat;
import org.nutz.lang.Files;
import org.nutz.lang.Strings;
import org.nutz.lang.random.R;
import org.nutz.lang.util.NutMap;
import org.nutz.log.Log;
import org.nutz.log.Logs;
import org.nutz.mvc.Mvcs;
import org.nutz.mvc.annotation.Param;
import org.nutz.mvc.upload.TempFile;

import net.wendal.nutzbook.common.util.RedisKey;
import net.wendal.nutzbook.common.util.Toolkit;
import net.wendal.nutzbook.core.bean.BigContent;
import net.wendal.nutzbook.core.bean.CResult;
import net.wendal.nutzbook.core.bean.User;
import net.wendal.nutzbook.core.bean.UserProfile;
import net.wendal.nutzbook.core.service.AppPushService;
import net.wendal.nutzbook.yvr.bean.SubForum;
import net.wendal.nutzbook.yvr.bean.Topic;
import net.wendal.nutzbook.yvr.bean.TopicReply;
import net.wendal.nutzbook.yvr.bean.TopicTag;
import net.wendal.nutzbook.yvr.bean.TopicType;
import redis.clients.jedis.Jedis;

@IocBean(create="init")
public class YvrService implements RedisKey, PubSub {

	private static final Log log = Logs.get();

	@Inject
	protected Dao dao;

	@Inject
	protected TopicSearchService topicSearchService;

	@Inject
	protected AppPushService appPushService;

	@Inject
	protected PubSubService pubSubService;

	@Inject
	protected BigContentService bigContentService;

	@Inject("java:$conf.get('topic.image.dir')")
	protected String imageDir;

	@Inject("java:$conf.get('topic.global.watchers')")
	protected String topicGlobalWatchers;
	
	@Inject
	PropertiesProxy conf;

	protected Set<Long> globalWatcherIds = new HashSet<>();

	@Aop("redis")
	public void fillTopic(Topic topic, Map<Long, UserProfile> authors) {
		if (topic.getUserId() == 0)
			topic.setUserId(1);
		topic.setAuthor(_cacheFetch(authors, topic.getUserId()));
		Double reply_count = jedis().zscore(RKEY_REPLY_COUNT, topic.getId());
		topic.setReplyCount(reply_count == null ? 0 : reply_count.intValue());
		if (topic.getReplyCount() > 0) {
			String replyId = jedis().hget(RKEY_REPLY_LAST, topic.getId());
			TopicReply reply = dao.fetch(TopicReply.class, replyId);
			if (reply != null) {
				if (reply.getUserId() == 0)
					reply.setUserId(1);
				reply.setAuthor(_cacheFetch(authors, reply.getUserId()));
				topic.setLastComment(reply);
			}
		}
		Double visited = jedis().zscore(RKEY_TOPIC_VISIT, topic.getId());
		topic.setVisitCount((visited == null) ? 0 : visited.intValue());
	}

	protected UserProfile _cacheFetch(Map<Long, UserProfile> authors, long userId) {
		if (authors == null)
			return null;
		UserProfile author = authors.get(userId);
		if (author == null) {
			author = dao.fetch(UserProfile.class, userId);
			authors.put(userId, author);
		}
		return author;
	}

	@Aop("redis")
	public String accessToken(String loginname) {
		return jedis().hget(RKEY_USER_ACCESSTOKEN, loginname);
	}

	@Aop("redis")
	public String accessToken(UserProfile profile) {
		String loginname = profile.getLoginname();
		String at = jedis().hget(RKEY_USER_ACCESSTOKEN, loginname);
		if (at == null) {
			// 双向绑定
			at = R.UU32();
			jedis().hset(RKEY_USER_ACCESSTOKEN, loginname, at);
			jedis().hset(RKEY_USER_ACCESSTOKEN2, at, loginname);
			jedis().hset(RKEY_USER_ACCESSTOKEN3, at, ""+profile.getUserId());
		}
		return at;
	}

	@Aop("redis")
	public void resetAccessToken(String loginname) {
		String at = jedis().hget(RKEY_USER_ACCESSTOKEN, loginname); {
			jedis().hdel(RKEY_USER_ACCESSTOKEN, loginname);
			jedis().hdel(RKEY_USER_ACCESSTOKEN2, at);
			jedis().hdel(RKEY_USER_ACCESSTOKEN3, at);
		}
	}

	@Aop("redis")
	public long getUserByAccessToken(String at) {
		String uid_str = jedis().hget(RKEY_USER_ACCESSTOKEN3, at);
		if (uid_str == null)
			return -1;
		return Long.parseLong(uid_str);
	}

	@Aop("redis")
	public CResult add(Topic topic, long userId) {
		if (userId < 1) {
			return _fail("请先登录");
		}
		User user = dao.fetch(User.class, userId);
        if (user == null) {
            return _fail("用户不存在");
        }
        if (user.isLocked()) {
            return _fail("用户已锁定");
        }
		if (Strings.isBlank(topic.getTitle()) || topic.getTitle().length() > 1024 || topic.getTitle().length() < 5) {
			return _fail("标题长度不合法");
		}
		if (Strings.isBlank(topic.getContent())) {
			return _fail("内容不合法");
		}
		if (topic.getTags() != null && topic.getTags().size() > 10) {
			return _fail("最多只能有10个tag");
		}
		if (0 != dao.count(Topic.class, Cnd.where("title", "=", topic.getTitle().trim()))) {
			return _fail("相同标题已经发过了");
		}
        if (needUserActive(userId, null)) {
            return CResult._fail("用户未激活,请前往打赏页激活");
        }
        String blackWorks = conf.get("yvr.black_words");
        if (!Strings.isBlank(blackWorks)) {
            for (String word : Strings.splitIgnoreBlank(blackWorks)) {
                if (topic.getTitle().contains(word)) {
                    log.infof("发帖标题[%s]黑名单[%s]命中!!! 锁定用户!! 拒绝发帖!!!", topic.getTitle(), word);
                    user.setLocked(true);
                    dao.update(user, "locked");
                    return CResult._fail("发帖标题被黑名单命中,你的账号已被锁定! 如果你认为这是错误,请与管理员联系");
                }
            }
        }
		// 检查关键字
		Set<String> tags = topic.getTags();
		topic.setTags(new HashSet<>());
		String oldTitle = topic.getTitle().trim();
		topic.setTitle(Strings.escapeHtml(topic.getTitle().trim()));
		topic.setUserId(userId);
		topic.setTop(false);
		//topic.setTags(new HashSet<String>());
		if (topic.getType() == null)
			topic.setType(TopicType.ask);
		topic.setContent(Toolkit.filteContent(topic.getContent()));
		topic.setContentId(bigContentService.put(topic.getContent()));
		topic.setContent(null);
		dao.insert(topic);
		// 如果是ask类型,把帖子加入到 "未回复"列表
		Jedis jedis = jedis();
		if (TopicType.ask.equals(topic.getType())) {
		    jedis.zadd(RKEY_TOPIC_NOREPLY, System.currentTimeMillis(), topic.getId());
		}
		jedis.zadd(RKEY_TOPIC_UPDATE + topic.getType(), System.currentTimeMillis(), topic.getId());
		if (topic.getType() == TopicType.ask || topic.getType() == TopicType.news) {
		    jedis.zadd(RKEY_TOPIC_UPDATE_ALL, System.currentTimeMillis(), topic.getId());
		    jedis.zincrby(RKEY_USER_SCORE, 100, ""+userId);
			String replyAuthorName = dao.fetch(User.class, userId).getName();
			for (Long watcherId : globalWatcherIds) {
			    if (watcherId != userId)
				pushUser(watcherId,
						"新帖:" + oldTitle,
						topic.getId(),
						replyAuthorName,
						topic.getTitle(),
						AppPushService.PUSH_TYPE_REPLY);
			}
		}
        pubSubService.fire("ps:topic:add", topic.getId());
        notifyWebSocket("home", "新帖:" + oldTitle, topic.getId(), userId);
        if (tags != null && tags.size() > 0) {
            updateTags(topic.getId(), tags);
            for (String tag : tags) {
                SubForum sf = dao.fetch(SubForum.class, tag);
                if (sf != null && sf.getMasters() != null && sf.getMasters().size() > 0) {
                    for (String master : sf.getMasters()) {
                        User u_master = dao.fetch(User.class, master);
                        if (u_master != null) {
                            pushUser(u_master.getId(),
                                     tag+"新帖:" + oldTitle,
                                     topic.getId(),
                                     dao.fetch(User.class, userId).getName(),
                                     topic.getTitle(),
                                     AppPushService.PUSH_TYPE_REPLY);
                        }
                    }
                }
            }
        }
		return _ok(topic.getId());
	}

	@Aop("redis")
	public CResult addReply(final String topicId, final TopicReply reply, final long userId) {
		if (userId < 1)
			return _fail("请先登录");
		if (reply == null || reply.getContent() == null || reply.getContent().trim().isEmpty()) {
			return _fail("内容不能为空");
		}
        User user = dao.fetch(User.class, userId);
        if (user == null) {
            return _fail("用户不存在");
        }
        if (user.isLocked()) {
            return _fail("用户已锁定");
        }
		final String cnt = reply.getContent().trim();
		final Topic topic = dao.fetch(Topic.class, topicId); // TODO 改成只fetch出type属性
		if (topic == null) {
			return _fail("主题不存在");
		}
		if (topic.isLock()) {
			return _fail("该帖子已经锁定,不能回复");
		}
        if (needUserActive(userId, null)) {
            return CResult._fail("用户未激活,请前往打赏页激活");
        }
		reply.setTopicId(topicId);
		reply.setUserId(userId);
		reply.setContent(Toolkit.filteContent(reply.getContent()));
		reply.setContentId(bigContentService.put(reply.getContent()));
		reply.setContent(null);
		dao.insert(reply);
		// 更新topic的时间戳
		Jedis jedis = jedis();
		if (topic.isTop()) {
		    jedis.zadd(RKEY_TOPIC_TOP, reply.getCreateTime().getTime(), topicId);
		} else {
		    jedis.zadd(RKEY_TOPIC_UPDATE + topic.getType(), reply.getCreateTime().getTime(), topicId);
			if (topic.getType() != TopicType.nb && topic.getType() != TopicType.shortit)
			    jedis.zadd(RKEY_TOPIC_UPDATE_ALL, reply.getCreateTime().getTime(), topicId);
		}
		jedis.zrem(RKEY_TOPIC_NOREPLY, topicId);
		if (topic.getTags() != null) {
			for (String tag : topic.getTags()) {
			    jedis.zadd(RKEY_TOPIC_TAG+tag.toLowerCase().trim(), reply.getCreateTime().getTime(), topicId);
			}
		}
		jedis.hset(RKEY_REPLY_LAST, topicId, reply.getId());
		jedis.zincrby(RKEY_REPLY_COUNT, 1, topicId);
		jedis.zincrby(RKEY_USER_SCORE, 10, ""+userId);

		notifyUsers(topic, reply, cnt, userId);
        pubSubService.fire("ps:topic:reply", topic.getId());
        notifyWebSocket("topic:"+topic.getId(), "新回复: " + topic.getTitle(), topic.getId(), userId);
		return _ok(reply.getId());
	}

	@Async
	protected void notifyUsers(Topic topic, TopicReply reply, String cnt, long userId) {
	    String replyAuthorName = dao.fetch(User.class, userId).getName();
        // 通知原本的作者
        if (topic.getUserId() != userId) {
            String alert = replyAuthorName + "回复了您的帖子";
            pushUser(topic.getUserId(),
                    alert,
                    topic.getId(),
                    replyAuthorName,
                    topic.getTitle(),
                    AppPushService.PUSH_TYPE_REPLY);
        }

        Set<String> ats = findAt(cnt, 5);
        for (String at : ats) {
            User user = dao.fetch(User.class, at);
            if (user == null)
                continue;
            if (topic.getUserId() == user.getId())
                continue; // 前面已经发过了
            if (userId == user.getId())
                continue; // 自己@自己, 忽略
            String alert = replyAuthorName + "在帖子回复中@了你";
            pushUser(user.getId(),
                    alert,
                    topic.getId(),
                    replyAuthorName,
                    topic.getTitle(),
                    AppPushService.PUSH_TYPE_AT);
        }
	}

	@Aop("redis")
	public CResult replyUp(String replyId, long userId) {
		if (userId < 1)
			return _fail("你还没登录呢");
		if (1 != dao.count(TopicReply.class, Cnd.where("id", "=", replyId))) {
			return _fail("没这条评论");
		}
		String key = RKEY_REPLY_LIKE + replyId;
		Double t = jedis().zscore(key, "" + userId);
		if (t != null) {
			jedis().zrem(key, userId + "");
			return _ok("down");
		} else {
			jedis().zadd(key, System.currentTimeMillis(), userId + "");
			return _ok("up");
		}
	}

	@Async
	protected void pushUser(long userId, String alert, String topic_id, String post_user, String topic_title, int type) {
	    topic_title = StringEscapeUtils.unescapeHtml(topic_title);
		Map<String, String> extras = new HashMap<String, String>();
		extras.put("topic_id", topic_id);
		extras.put("post_user", post_user);
		extras.put("topic_title", topic_title);
		// 通知类型
		extras.put("type", type+"");
		appPushService.alert(userId, alert, topic_title, extras);
	}

	public List<Topic> getRecentTopics(long userId, Pager pager) {
		List<Topic> recent_topics = dao.query(Topic.class, Cnd.where("userId", "=", userId).desc("createTime"), pager);

		Map<Long, UserProfile> authors = new HashMap<>();
		if (!recent_topics.isEmpty()) {
			for (Topic topic : recent_topics) {
				fillTopic(topic, authors);
			}
		}
		pager.setRecordCount(dao.count(Topic.class, Cnd.where("userId", "=", userId)));
		return recent_topics;

	}

	public List<Topic> getRecentReplyTopics(long userId, Pager pager) {

		Map<Long, UserProfile> authors = new HashMap<>();
		Cnd cnd = Cnd.where("userId", "=", userId);
		cnd.desc("createTime");

		Sql sql = Sqls.queryString("select DISTINCT topicId from t_topic_reply $cnd").setEntity(dao.getEntity(TopicReply.class)).setVar("cnd", cnd);
		pager.setRecordCount(dao.execute(Sqls.fetchInt("select count(DISTINCT topicId) from t_topic_reply $cnd").setEntity(dao.getEntity(TopicReply.class)).setVar("cnd", cnd)).getInt());
		sql.setPager(pager);
		String[] replies_topic_ids = dao.execute(sql).getObject(String[].class);
		List<Topic> recent_replies = new ArrayList<Topic>();
		for (String topic_id : replies_topic_ids) {
			Topic _topic = dao.fetch(Topic.class, topic_id);
			if (_topic == null)
				continue;
			recent_replies.add(_topic);
		}
		if (!recent_replies.isEmpty()) {
			for (Topic topic : recent_replies) {
				fillTopic(topic, authors);
			}
		}
		return recent_replies;
	}

	public NutMap upload(TempFile tmp, long userId) throws IOException {
		NutMap re = new NutMap();
		if (userId < 1)
			return re.setv("msg", "请先登陆!");
		if (tmp == null || tmp.getSize() == 0) {
			return re.setv("msg", "空文件");
		}
		if (tmp.getSize() > 10 * 1024 * 1024) {
			tmp.delete();
			return re.setv("msg", "文件太大了");
		}
		String id = R.UU32();
		String path = "/" + id.substring(0, 2) + "/" + id.substring(2);
		File f = new File(imageDir + path);
		Files.createNewFile(f);
		Files.write(f, tmp.getInputStream());
		tmp.delete();
		re.put("url", Mvcs.getServletContext().getContextPath()+"/yvr/upload" + path);
		re.setv("success", true);
		return re;
	}

	public void init() {
		if (topicGlobalWatchers != null) {
			for (String username : Strings.splitIgnoreBlank(topicGlobalWatchers)) {
				User user = dao.fetch(User.class, username);
				if (user == null) {
					log.infof("no such user[name=%s] for topic watch", username);
					continue;
				}
				globalWatcherIds.add(user.getId());
			}
		}
		pubSubService.reg("ps:topic:*", this);
	}

	static Pattern atPattern = Pattern.compile("@([a-zA-Z0-9\\_]{4,20}\\s)");

	public static Set<String> findAt(String cnt, int limit) {
		Set<String> ats = new HashSet<String>();
		Matcher matcher = atPattern.matcher(cnt+" ");
		int start = 0;
		int end = 0;
		while (end < cnt.length() && matcher.find(end)) {
			start = matcher.start();
			end = matcher.end();
			ats.add(cnt.substring(start+1, end-1).trim().toLowerCase());
			if (limit <= ats.size())
				break;
		}
		return ats;
	}


	@Aop("redis")
	public boolean updateTags(String topicId, @Param("tags")Set<String> tags) {
		if (Strings.isBlank(topicId) || tags == null) {
			return false;
		}
		Topic topic = dao.fetch(Topic.class, topicId);
		if (topic == null)
			return false;
		Set<String> oldTags = topic.getTags();
		if (oldTags == null)
			oldTags = new HashSet<>();
		log.debugf("update from '%s' to '%s'", oldTags, tags);
		topic.setTags(tags);
		topic.setUpdateTime(new Date());
		dao.update(topic, "tags|updateTime");
		Set<String> newTags = new HashSet<>(tags);
		newTags.removeAll(oldTags);
		Set<String> removeTags = new HashSet<>(oldTags);;
		removeTags.removeAll(tags);
		fillTopic(topic, null);
		Date lastReplyTime = topic.getCreateTime();
		if (topic.getLastComment() != null)
			lastReplyTime = topic.getLastComment().getCreateTime();
		Jedis jedis = jedis();
		for (String tag : removeTags) {
		    jedis.zrem(RKEY_TOPIC_TAG+tag.toLowerCase().trim(), topic.getId());
		    jedis.zincrby(RKEY_TOPIC_TAG_COUNT, -1, tag.toLowerCase().trim());
		}
		for (String tag : newTags) {
		    jedis.zadd(RKEY_TOPIC_TAG+tag.toLowerCase().trim(), lastReplyTime.getTime(), topic.getId());
		    jedis.zincrby(RKEY_TOPIC_TAG_COUNT, 1, tag.toLowerCase().trim());
		}
		return true;
	}

	@Aop("redis")
	public List<Topic> fetchTop() {
		List<Topic> list = new ArrayList<>();
		Map<Long, UserProfile> authors = new HashMap<>();
		for(String id :jedis().zrevrangeByScore(RKEY_TOPIC_TOP, Long.MAX_VALUE, 0)) {
			Topic topic = dao.fetch(Topic.class, id);
			if (topic == null)
				continue;
			fillTopic(topic, authors);
			list.add(topic);
		}
		return list;
	}

	@Aop("redis")
	public List<TopicTag> fetchTopTags() {
		Set<String> names = jedis().zrevrangeByScore(RKEY_TOPIC_TAG_COUNT, Long.MAX_VALUE, 0, 0, 20);
		List<TopicTag> tags = new ArrayList<>();
		Jedis jedis = jedis();
		for (String name: names) {
			tags.add(new TopicTag(name, jedis.zscore(RKEY_TOPIC_TAG_COUNT, name).intValue()));
		}
		return tags;
	}

	@Aop("redis")
	public boolean checkNonce(String nonce, String time) {
		try {
			long t = Long.parseLong(time);
			if (System.currentTimeMillis() - t > 10*60*1000) {
				return false;
			}
			String key = "at:nonce:"+nonce;
			Long re = jedis().setnx(key, "");
			if (re == 0) {
				return false;
			}
			jedis().expire(key, 10*60);
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

    @Async
	@Aop("redis")
	public void updateTopicTypeCount() {
		for (TopicType tt : TopicType.values()) {
			tt.count = jedis().zcount(RKEY_TOPIC_UPDATE+tt.name(), "-inf", "+inf");
		}
	}

    protected void notifyWebSocket(String room, Object data, String tag, long byuser) {
        NutMap map = new NutMap();
        map.put("action", "notify");
        map.put("data", data);
        map.put("options", new NutMap().setv("tag", tag));
        map.put("byuser", byuser);
        String msg = Json.toJson(map, JsonFormat.compact());
        pubSubService.fire("wsroom:"+room, msg);
    }

    @Override
    public void onMessage(String channel, String message) {
        log.debugf("channel=%s, msg=%s", channel, message);
        switch (channel) {
        // TODO 数据库集群的delay会导致
        case "ps:topic:add":
            updateTopicTypeCount();
            topicSearchService.addIndex(dao.fetch(Topic.class, message), true);
            break;
        case "ps:topic:reply":
            topicSearchService.addIndex(dao.fetch(Topic.class, message), false);
            break;
        default:
            break;
        }
    }
    
    /**
     * 收藏/取消收藏
     * @param topicId 帖子id
     * @param uid 用户id
     * @param markOrUnmark true,如果是收藏. false, 取消收藏
     * @return 是否成功
     */
    @Aop("redis")
    public boolean topicMark(String topicId, long uid) {
        if (uid < 1)
            return false;
        if (dao.count(Topic.class, Cnd.where("id", "=", topicId)) == 0)
            return false;
        if (!jedis().sismember(RKEY_TOPIC_MARK+topicId, ""+uid)) {
            jedis().sadd(RKEY_TOPIC_MARK+topicId, ""+uid);
            jedis().zadd(RKEY_USER_TOPIC_MARK+uid, System.currentTimeMillis(), topicId);
        } else {
            jedis().srem(RKEY_TOPIC_MARK+topicId, ""+uid);
            jedis().zrem(RKEY_USER_TOPIC_MARK+uid, topicId);
        }
        return true;
    }
    
    public NutMap topicDelete(String topicId) {
     // 首先, 删除关系型数据库里面的记录
        // 先删除t_topic表
        Topic topic = dao.fetch(Topic.class, topicId);
        if (topic == null)
            return new NutMap("ok", false).setv("msg", "帖子不存在");
        dao.delete(topic);
        dao.delete(BigContent.class, topic.getContentId());
        // 然后删除评论
        List<TopicReply> replies = dao.query(TopicReply.class, Cnd.where("topicId", "=", topicId));
        for (TopicReply reply : replies) {
            dao.delete(reply);
            dao.delete(BigContent.class, reply.getContentId());
        }
        
        // 然后清除分类表里面的数据
        jedis().zrem(RKEY_TOPIC_UPDATE+topic.getType().getName(), topicId);
        // 清除总索引
        jedis().zrem(RKEY_TOPIC_UPDATE + "all", topic.getId());
        // 如果是精华帖,那还得删掉精华帖里面的记录,可能性不大吧
        if (topic.isGood()) {
            jedis().zrem(RKEY_TOPIC_UPDATE + "good", topic.getId());
        }
        // 如果是置顶帖呢? 丧心病狂啊
        if (topic.isTop()) {
            jedis().zrem(RKEY_TOPIC_TOP, topic.getId());
        }
        // 还得清除访问计数器
        jedis().zrem(RKEY_TOPIC_VISIT, topic.getId());
        
        // TODO 清除各用户的收藏记录
        //jedis().del("");
        
        updateTopicTypeCount();
        
        return new NutMap("ok", true);
    }
    
    // 先预留,还没想好怎么做
    @Aop("redis")
    public void rebuildRedisUpdateList() {
        
    }
    
    public boolean needUserActive(long userId, HttpSession session) {
        if (!conf.getBoolean("yvr.pay_before_first_topic", true)) {
            return false;
        }
        User user = dao.fetch(User.class, userId);
        if (user == null)
            return true;
        if (user.isLocked()) {
            if (session != null)
                session.invalidate();
            return true;
        }
        // 2018-07-06 00:00:00 之后注册的用户,要先打赏才能发帖或评论
        if (user.getCreateTime().getTime() < 1530806400000L) {
            return false;
        }
        int sum = dao.func("t_bee_payment", "sum", "transaction_fee", Cnd.where("trade_success", "=", true).and("from_user", "=", userId));
        if (sum >= 38 && user.getCreateTime().getTime() <= 1545316950000L) { // 大概是2018-12-21 00:00:00
            return false;
        }
        return sum < conf.getInt("yvr.tips_for_active", 200);
    }
}
