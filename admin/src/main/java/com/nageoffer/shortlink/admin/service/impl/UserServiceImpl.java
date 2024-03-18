package com.nageoffer.shortlink.admin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.UUID;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.exception.ServiceException;
import com.nageoffer.shortlink.admin.common.enums.UserErrorCodeEnum;
import com.nageoffer.shortlink.admin.dao.entity.UserDO;
import com.nageoffer.shortlink.admin.dao.mapper.UserMapper;
import com.nageoffer.shortlink.admin.dto.req.UserLoginReqDTO;
import com.nageoffer.shortlink.admin.dto.req.UserRegisterReqDTO;
import com.nageoffer.shortlink.admin.dto.req.UserUpdateReqDTO;
import com.nageoffer.shortlink.admin.dto.resp.UserLoginRespDTO;
import com.nageoffer.shortlink.admin.dto.resp.UserRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import com.nageoffer.shortlink.admin.service.UserService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.nageoffer.shortlink.admin.common.constant.RedisCacheConstant.LOCK_USER_REGISTER_KEY;
import static com.nageoffer.shortlink.admin.common.constant.RedisCacheConstant.USER_LOGIN_KEY;
import static com.nageoffer.shortlink.admin.common.enums.UserErrorCodeEnum.USER_EXIST;
import static com.nageoffer.shortlink.admin.common.enums.UserErrorCodeEnum.USER_SAVE_ERROR;

/**
 * 用户接口实现层
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    private final RBloomFilter<String> userRegisterCachePenetrationBloomFilter;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final GroupService groupService;

    @Override
    public UserRespDTO getUserByUsername(String username) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username);
        UserDO userDo = baseMapper.selectOne(queryWrapper);
        if (userDo == null) {
            throw new ServiceException(UserErrorCodeEnum.USER_NULL);
        }
        UserRespDTO result = new UserRespDTO();
        BeanUtils.copyProperties(userDo, result);
        return result;
    }

    /**
     * 查询用户名是否可用
     *
     * @param username 用户名
     * @return 用户名可用返回 true，否则返回 false
     */
    @Override
    public Boolean hasUsername(String username) {
        // 布隆过滤器中不包含该用户名, 则返回 true, 表示用户名可用
        return !userRegisterCachePenetrationBloomFilter.contains(username);
    }

    @Override
    public void register(UserRegisterReqDTO requestParam) {
        // 用户名是否存在
        if (!hasUsername(requestParam.getUsername())) {
            throw new ClientException(UserErrorCodeEnum.USER_NAME_EXIST);
        }
        // 用户名加分布式锁, 防止并发注册, SET lock_key value NX PX 30000, 如果key不存在, 则获取锁
        RLock lock = redissonClient.getLock(LOCK_USER_REGISTER_KEY + requestParam.getUsername());

        try {
            // 如果获取到锁, 则执行注册逻辑
            if (lock.tryLock()) {
                try {
                    int inserted = baseMapper.insert(BeanUtil.toBean(requestParam, UserDO.class));
                    if (inserted < 1) {
                        throw new ClientException(USER_SAVE_ERROR);
                    }
                } catch (DuplicateKeyException ex) { // DuplicateKeyException表示尝试向数据库插入或更新记录时发生重复键（即主键或唯一键）的异常
                    throw new ClientException(USER_EXIST);
                }
                // 用户名加入布隆过滤器
                userRegisterCachePenetrationBloomFilter.add(requestParam.getUsername());
                // 新增默认分组(每个用户都有一个默认分组)
                groupService.saveGroup(requestParam.getUsername(), "默认分组");
                return;
            }
            // 如果没有获取到锁, 则抛出异常用户名已存在
            throw new ClientException(UserErrorCodeEnum.USER_NAME_EXIST);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void update(UserUpdateReqDTO requestParam) {
        // TODO 验证当前用户名是否为登录用户
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());

        int update = baseMapper.update(BeanUtil.toBean(requestParam, UserDO.class), updateWrapper);
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername())
                .eq(UserDO::getPassword, requestParam.getPassword())
                .eq(UserDO::getDelFlag, 0);
        UserDO userDO = baseMapper.selectOne(queryWrapper);

        // 如果用户不存在, 则抛出异常
        if (userDO == null) {
            throw new ClientException("用户不存在");
        }

        // 如果用户已登录, 则刷新Token有效期
        Map<Object, Object> hasLoginMap = stringRedisTemplate.opsForHash().entries(USER_LOGIN_KEY + requestParam.getUsername());
        if (CollUtil.isNotEmpty(hasLoginMap)) {
            // 如果用户已登录, 则刷新Token有效期
            stringRedisTemplate.expire(USER_LOGIN_KEY + requestParam.getUsername(), 30L, TimeUnit.MINUTES);
            String token = hasLoginMap.keySet().stream()
                    .findFirst()
                    .map(Object::toString)
                    .orElseThrow(() -> new ClientException("用户登录错误")); // 如果存在登录信息, 则返回Token
            return new UserLoginRespDTO(token);
        }

        /**
         * Hash
         * Key: login_用户名
         * Value:
         *  Key: token标识
         *  Value: JSON字符串(用户信息)
         */
        // 如果用户未登录, 则生成Token, 并将用户信息存入Redis
        String uuid = UUID.randomUUID().toString();
        stringRedisTemplate.opsForHash().put(USER_LOGIN_KEY + requestParam.getUsername(), uuid, JSON.toJSONString(userDO));
        stringRedisTemplate.expire(USER_LOGIN_KEY + requestParam.getUsername(), 30L, TimeUnit.MINUTES);
        return new UserLoginRespDTO(uuid);
    }

    @Override
    public Boolean checkLogin(String username, String token) {
        // 判断用户是否登录
        return stringRedisTemplate.opsForHash().get(USER_LOGIN_KEY + username, token) != null;
    }

    @Override
    public void logout(String username, String token) {
        // 判断用户是否登录, 如果登录, 则删除用户登录信息
        if (checkLogin(username, token)) {
            stringRedisTemplate.delete(USER_LOGIN_KEY + username);
            return;
        }
        throw new ClientException("用户Token不存在或用户未登录");
    }
}
