package io.netty.example.inaction.ch9;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import java.util.List;

/**
 * FrameChunkDecoder
 *
 * @author YJ
 * @date 2022/3/29
 **/
public class FrameChunkDecoder extends ByteToMessageDecoder {

    private final int maxFrameSize;

    public FrameChunkDecoder(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
        throws Exception {
        int readableBytes = in.readableBytes();
        if (readableBytes > maxFrameSize) {
            // discard the bytes in.clear();
            throw new TooLongFrameException();
        }

        ByteBuf buf = in.readBytes(readableBytes);
        out.add(buf);
    }
}
