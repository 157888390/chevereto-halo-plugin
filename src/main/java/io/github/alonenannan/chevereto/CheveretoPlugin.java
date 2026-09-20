package io.github.alonenannan.chevereto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;

@Slf4j
@Component
public class CheveretoPlugin extends BasePlugin {

    @Override
    public void start() {
        log.info("Chevereto 图床插件启动成功");
    }

    @Override
    public void stop() {
        log.info("Chevereto 图床插件已停止");
    }
}
