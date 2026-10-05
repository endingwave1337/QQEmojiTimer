package com.endingwave.qqemoji.hook;

import com.endingwave.qqemoji.config.Config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 决定这一次要发给谁。 */
public class Targets {

    private Targets() {
    }

    public static List<String> resolve(Config cfg, List<RecentContacts.Item> recents) {
        Set<String> out = new LinkedHashSet<String>();

        if (cfg.allPinned) {
            Set<String> pinned = pinnedUins(recents);
            MainHook.log("置顶会话: " + pinned);
            out.addAll(pinned);
            return new ArrayList<String>(out);
        }

        for (String raw : cfg.targets) {
            String uin = raw == null ? "" : raw.trim();
            if (uin.matches("[0-9]{5,12}")) {
                out.add(uin);
            } else {
                MainHook.log("忽略非法 QQ 号: " + raw);
            }
        }

        if (cfg.pinnedOnly) {
            if (recents.isEmpty()) {
                MainHook.log("读不到会话列表，本次跳过置顶校验直接发送");
            } else {
                out.retainAll(pinnedUins(recents));
            }
        }
        return new ArrayList<String>(out);
    }

    private static Set<String> pinnedUins(List<RecentContacts.Item> recents) {
        Set<String> pinned = new LinkedHashSet<String>();
        for (RecentContacts.Item it : recents) {
            if (it.top && it.chatType == 1 && !it.uin.isEmpty()) {
                pinned.add(it.uin);
            }
        }
        return pinned;
    }
}
