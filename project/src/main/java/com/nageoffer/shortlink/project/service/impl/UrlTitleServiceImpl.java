package com.nageoffer.shortlink.project.service.impl;

import com.nageoffer.shortlink.project.service.UrlTitleService;
import lombok.SneakyThrows;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * URL 标题接口实现层
 */
@Service
public class UrlTitleServiceImpl implements UrlTitleService {

    @SneakyThrows
    @Override
    public String getTitleByUrl(String url) {
        // 使用URL类将给定的URL转换为URL对象
        URL targetUrl = new URL(url);
        // 打开一个HTTP连接到该URL
        HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
        // 设置HTTP请求方法为GET，并连接到该URL
        connection.setRequestMethod("GET");
        connection.connect();
        // 获取HTTP响应码
        int responseCode = connection.getResponseCode();
        // 如果响应码为200，表示请求成功
        if (responseCode == HttpURLConnection.HTTP_OK) {
            // 使用Jsoup连接到给定的URL并获取网页文档
            Document document = Jsoup.connect(url).get();
            // 返回网页标题
            return document.title();
        }
        // 如果请求失败，返回错误信息
        return "Error while fetching title.";
    }
}