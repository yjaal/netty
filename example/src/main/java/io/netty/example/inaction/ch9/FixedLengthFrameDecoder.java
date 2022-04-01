package io.netty.example.inaction.ch9;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * 生成指定长度的帧
 *
 * @author YJ
 * @date 2022/3/29
 **/
public class FixedLengthFrameDecoder extends ByteToMessageDecoder {

    private final int frameLen;

    public FixedLengthFrameDecoder(int frameLen) {
        if (frameLen <= 0) {
            throw new IllegalArgumentException(
                "frameLen must be a positive integer: " + frameLen);
        }
        this.frameLen = frameLen;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
        throws Exception {
        while (in.readableBytes() >= frameLen) {
            ByteBuf buf = in.readBytes(frameLen);
            out.add(buf);
        }
    }
}
