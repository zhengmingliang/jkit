package com.alianga.jkit.image;

/**
 *
 * @author wuhongjun
 * @version 1.0
 */
public class Quant {
    /**
     * 神经元个数，即量化后调色板的颜色数量
     */
    protected static final int netsize = 256; /* number of colours used */

    /* four primes near 500 - assume no image has a length so large */
    /* that it is divisible by all four primes */
    /**
     * 采样步长使用的第一个接近 500 的质数
     */
    protected static final int prime1 = 499;
    /**
     * 采样步长使用的第二个接近 500 的质数
     */
    protected static final int prime2 = 491;
    /**
     * 采样步长使用的第三个接近 500 的质数
     */
    protected static final int prime3 = 487;
    /**
     * 采样步长使用的第四个接近 500 的质数
     */
    protected static final int prime4 = 503;

    /**
     * 输入图像的最小字节数，小于该值时不做抽样
     */
    protected static final int minpicturebytes = (3 * prime4);
    /* minimum size for input image */

    /* Program Skeleton
       ----------------
       [select samplefac in range 1..30]
       [read image from input file]
       pic = (unsigned char*) malloc(3*width*height);
       initnet(pic,3*width*height,samplefac);
       learn();
       unbiasnet();
       [write output image header, using writecolourmap(f)]
       inxbuild();
       write output image using inxsearch(b,g,r) */

    /* Network Definitions
       ------------------- */

    /**
     * 神经元的最大下标，等于 {@code netsize - 1}
     */
    protected static final int maxnetpos = (netsize - 1);
    /**
     * 颜色分量在网络中放大的位数
     */
    protected static final int netbiasshift = 4; /* bias for colour values */
    /**
     * 学习循环的周期数
     */
    protected static final int ncycles = 100; /* no. of learning cycles */

    /* defs for freq and bias */
    /**
     * 频率与偏置计算中小数放大的位数
     */
    protected static final int intbiasshift = 16; /* bias for fractions */
    /**
     * 小数放大的基数，等于 {@code 1 << intbiasshift}
     */
    protected static final int intbias = (1 << intbiasshift);
    /**
     * gamma 的位移量，对应 gamma 取值 1024
     */
    protected static final int gammashift = 10; /* gamma = 1024 */
    /**
     * gamma 值，等于 {@code 1 << gammashift}
     */
    protected static final int gamma = (1 << gammashift);
    /**
     * beta 的位移量
     */
    protected static final int betashift = 10;
    /**
     * beta 值，相当于 1/1024
     */
    protected static final int beta = (intbias >> betashift); /* beta = 1/1024 */
    /**
     * beta 与 gamma 的乘积，用于更新胜出神经元的偏置
     */
    protected static final int betagamma =
            (intbias << (gammashift - betashift));

    /* defs for decreasing radius factor */
    /**
     * 邻域学习的初始半径，256 色时为 32
     */
    protected static final int initrad = (netsize >> 3); /* for 256 cols, radius starts */
    /**
     * 半径放大的位数
     */
    protected static final int radiusbiasshift = 6; /* at 32.0 biased by 6 bits */
    /**
     * 半径放大的基数，等于 {@code 1 << radiusbiasshift}
     */
    protected static final int radiusbias = (1 << radiusbiasshift);
    /**
     * 放大后的初始邻域半径
     */
    protected static final int initradius = (initrad * radiusbias); /* and decreases by a */
    /**
     * 半径衰减因子，每个周期衰减 1/30
     */
    protected static final int radiusdec = 30; /* factor of 1/30 each cycle */

    /* defs for decreasing alpha factor */
    /**
     * 学习率放大的位数，学习率初值相当于 1.0
     */
    protected static final int alphabiasshift = 10; /* alpha starts at 1.0 */
    /**
     * 放大后的初始学习率，等于 {@code 1 << alphabiasshift}
     */
    protected static final int initalpha = (1 << alphabiasshift);

    /**
     * 学习率的衰减因子，由采样因子推导，按 10 位放大
     */
    protected int alphadec; /* biased by 10 bits */

    /* radbias and alpharadbias used for radpower calculation */
    /**
     * radpower 计算中半径放大的位数
     */
    protected static final int radbiasshift = 8;
    /**
     * radpower 计算中半径放大的基数，等于 {@code 1 << radbiasshift}
     */
    protected static final int radbias = (1 << radbiasshift);
    /**
     * 学习率与半径放大位数之和
     */
    protected static final int alpharadbshift = (alphabiasshift + radbiasshift);
    /**
     * 邻域调整时用于还原放大倍数的基数，等于 {@code 1 << alpharadbshift}
     */
    protected static final int alpharadbias = (1 << alpharadbshift);

    /* Types and Global Variables
    -------------------------- */

    /**
     * 待量化的原始图像像素数据，按每像素三字节顺序存放
     */
    protected byte[] thepicture; /* the input image itself */
    /**
     * 图像数据的字节长度，等于 高 * 宽 * 3
     */
    protected int lengthcount; /* lengthcount = H*W*3 */

    /**
     * 采样因子，取值 1..30，值越大学习越快、精度越低
     */
    protected int samplefac; /* sampling factor 1..30 */

    //   typedef int pixel[4];                /* BGRc */
    /**
     * 神经网络本身，大小为 {@code [netsize][4]}，每行依次存放三个颜色分量与颜色序号
     */
    protected int[][] network; /* the network itself - [netsize][4] */

    /**
     * 按绿色分量建立的网络查找索引，供 {@link #map(int, int, int)} 快速定位
     */
    protected int[] netindex = new int[256];
    /* for network lookup - really 256 */

    /**
     * 各神经元的偏置数组，用于抑制被频繁选中的神经元
     */
    protected int[] bias = new int[netsize];
    /* bias and freq arrays for learning */
    /**
     * 各神经元被选中的频率数组
     */
    protected int[] freq = new int[netsize];
    /**
     * 预先计算的邻域学习强度数组，下标为与胜出神经元的距离
     */
    protected int[] radpower = new int[initrad];
    /* radpower for precomputation */

    /* Initialise network in range (0,0,0) to (255,255,255) and set parameters
       ----------------------------------------------------------------------- */
    /**
     * 构建量化器，并把神经网络初始化为 (0,0,0) 到 (255,255,255) 的均匀分布。
     *
     * @param thepic 待量化的图像像素数据，按每像素三字节顺序存放
     * @param len    图像数据的有效字节长度，等于 高 * 宽 * 3
     * @param sample 采样因子，取值 1..30，值越大学习越快、精度越低
     */
    public Quant(byte[] thepic, int len, int sample) {
        int i;
        int[] p;

        thepicture = thepic;
        lengthcount = len;
        samplefac = sample;

        network = new int[netsize][];
        for (i = 0; i < netsize; i++) {
            network[i] = new int[4];
            p = network[i];
            p[0] = p[1] = p[2] = (i << (netbiasshift + 8)) / netsize;
            freq[i] = intbias / netsize; /* 1/netsize */
            bias[i] = 0;
        }
    }

    /**
     * 按颜色序号输出量化后的调色板。
     *
     * @return 长度为 {@code 3 * netsize} 的调色板字节数组，按颜色序号依次存放每个颜色的三个分量
     */
    public byte[] colorMap() {
        byte[] map = new byte[3 * netsize];
        int[] index = new int[netsize];
        for (int i = 0; i < netsize; i++) {
            index[network[i][3]] = i;
        }
        int k = 0;
        for (int i = 0; i < netsize; i++) {
            int j = index[i];
            map[k++] = (byte) (network[j][0]);
            map[k++] = (byte) (network[j][1]);
            map[k++] = (byte) (network[j][2]);
        }
        return map;
    }

    /* Insertion sort of network and building of netindex[0..255] (to do after unbias)
       ------------------------------------------------------------------------------- */
    /**
     * 按绿色分量对神经网络做插入排序，并构建 {@code netindex[0..255]} 查找索引，需在去偏置之后调用。
     */
    public void inxbuild() {
        int i;
        int j;
        int smallpos;
        int smallval;
        int[] p;
        int[] q;

        int previouscol = 0;
        int startpos = 0;
        for (i = 0; i < netsize; i++) {
            p = network[i];
            smallpos = i;
            smallval = p[1]; /* index on g */
            /* find smallest in i..netsize-1 */
            for (j = i + 1; j < netsize; j++) {
                q = network[j];
                if (q[1] < smallval) { /* index on g */
                    smallpos = j;
                    smallval = q[1]; /* index on g */
                }
            }
            q = network[smallpos];
            /* swap p (i) and q (smallpos) entries */
            if (i != smallpos) {
                j = q[0];
                q[0] = p[0];
                p[0] = j;
                j = q[1];
                q[1] = p[1];
                p[1] = j;
                j = q[2];
                q[2] = p[2];
                p[2] = j;
                j = q[3];
                q[3] = p[3];
                p[3] = j;
            }
            /* smallval entry is now in position i */
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) >> 1;
                for (j = previouscol + 1; j < smallval; j++) {
                    netindex[j] = i;
                }
                previouscol = smallval;
                startpos = i;
            }
        }
        netindex[previouscol] = (startpos + maxnetpos) >> 1;
        for (j = previouscol + 1; j < 256; j++) {
            netindex[j] = maxnetpos; /* really 256 */
        }
    }

    /* Main Learning Loop
       ------------------ */
    /**
     * 执行主学习循环，按采样因子抽取像素并不断收敛学习率与邻域半径，使网络逼近图像的颜色分布。
     */
    public void learn() {
        int i;
        int j;
        int b;
        int g;
        int r;
        int step;

        if (lengthcount < minpicturebytes) {
            samplefac = 1;
        }
        alphadec = 30 + ((samplefac - 1) / 3);
        byte[] p = thepicture;
        int pix = 0;
        int lim = lengthcount;
        int samplepixels = lengthcount / (3 * samplefac);
        int delta = samplepixels / ncycles;
        int alpha = initalpha;
        int radius = initradius;

        int rad = radius >> radiusbiasshift;
        if (rad <= 1) {
            rad = 0;
        }
        for (i = 0; i < rad; i++) {
            radpower[i] =
                    alpha * (((rad * rad - i * i) * radbias) / (rad * rad));
        }

        //fprintf(stderr,"beginning 1D learning: initial radius=%d\n", rad);

        if (lengthcount < minpicturebytes) {
            step = 3;
        } else if ((lengthcount % prime1) != 0) {
            step = 3 * prime1;
        } else {
            if ((lengthcount % prime2) != 0) {
                step = 3 * prime2;
            } else {
                if ((lengthcount % prime3) != 0) {
                    step = 3 * prime3;
                } else {
                    step = 3 * prime4;
                }
            }
        }

        i = 0;
        while (i < samplepixels) {
            b = (p[pix] & 0xff) << netbiasshift;
            g = (p[pix + 1] & 0xff) << netbiasshift;
            r = (p[pix + 2] & 0xff) << netbiasshift;
            j = contest(b, g, r);

            altersingle(alpha, j, b, g, r);
            if (rad != 0) {
                alterneigh(rad, j, b, g, r); /* alter neighbours */
            }

            pix += step;
            if (pix >= lim) {
                pix -= lengthcount;
            }

            i++;
            if (delta == 0) {
                delta = 1;
            }
            if (i % delta == 0) {
                alpha -= alpha / alphadec;
                radius -= radius / radiusdec;
                rad = radius >> radiusbiasshift;
                if (rad <= 1) {
                    rad = 0;
                }
                for (j = 0; j < rad; j++) {
                    radpower[j] =
                            alpha * (((rad * rad - j * j) * radbias) / (rad * rad));
                }
            }
        }
        //fprintf(stderr,"finished 1D learning: final alpha=%f !\n",((float)alpha)/initalpha);
    }

    /* Search for BGR values 0..255 (after net is unbiased) and return colour index
       ---------------------------------------------------------------------------- */
    /**
     * 查找与给定颜色最接近的调色板颜色序号，需在网络去偏置并建立索引之后调用。
     *
     * @param b 第一个颜色分量，取值 0..255
     * @param g 第二个颜色分量，取值 0..255，同时作为索引查找的键
     * @param r 第三个颜色分量，取值 0..255
     * @return 曼哈顿距离最近的颜色在调色板中的序号；网络为空时返回 -1
     */
    public int map(int b, int g, int r) {
        int dist;
        int a;
        int[] p;

        int bestd = 1000; /* biggest possible dist is 256*3 */
        int best = -1;
        int i = netindex[g]; /* index on g */
        int j = i - 1; /* start at netindex[g] and work outwards */

        while ((i < netsize) || (j >= 0)) {
            if (i < netsize) {
                p = network[i];
                dist = p[1] - g; /* inx key */
                if (dist >= bestd) {
                    i = netsize; /* stop iter */
                } else {
                    i++;
                    if (dist < 0) {
                        dist = -dist;
                    }
                    a = p[0] - b;
                    if (a < 0) {
                        a = -a;
                    }
                    dist += a;
                    if (dist < bestd) {
                        a = p[2] - r;
                        if (a < 0) {
                            a = -a;
                        }
                        dist += a;
                        if (dist < bestd) {
                            bestd = dist;
                            best = p[3];
                        }
                    }
                }
            }
            if (j >= 0) {
                p = network[j];
                dist = g - p[1]; /* inx key - reverse dif */
                if (dist >= bestd) {
                    j = -1; /* stop iter */
                } else {
                    j--;
                    if (dist < 0) {
                        dist = -dist;
                    }
                    a = p[0] - b;
                    if (a < 0) {
                        a = -a;
                    }
                    dist += a;
                    if (dist < bestd) {
                        a = p[2] - r;
                        if (a < 0) {
                            a = -a;
                        }
                        dist += a;
                        if (dist < bestd) {
                            bestd = dist;
                            best = p[3];
                        }
                    }
                }
            }
        }
        return (best);
    }

    /**
     * 依次执行学习、去偏置与索引构建，完成整个量化流程。
     *
     * @return 量化后的调色板字节数组，内容同 {@link #colorMap()}
     */
    public byte[] process() {
        learn();
        unbiasnet();
        inxbuild();
        return colorMap();
    }

    /* Unbias network to give byte values 0..255 and record position i to prepare for sort
       ----------------------------------------------------------------------------------- */
    /**
     * 对网络去偏置，把各颜色分量还原为 0..255 的取值，并把当前下标记录为颜色序号以便后续排序。
     */
    public void unbiasnet() {
        int i; // j;

        for (i = 0; i < netsize; i++) {
            network[i][0] >>= netbiasshift;
            network[i][1] >>= netbiasshift;
            network[i][2] >>= netbiasshift;
            network[i][3] = i; /* record colour no */
        }
    }

    /* Move adjacent neurons by precomputed alpha*(1-((i-j)^2/[r]^2)) in radpower[|i-j|]
       --------------------------------------------------------------------------------- */
    /**
     * 按 {@code radpower} 中预计算的强度，把胜出神经元两侧半径范围内的邻居向目标颜色移动。
     *
     * @param rad 邻域半径
     * @param i   胜出神经元的下标
     * @param b   目标颜色的第一个分量（已放大）
     * @param g   目标颜色的第二个分量（已放大）
     * @param r   目标颜色的第三个分量（已放大）
     */
    protected void alterneigh(int rad, int i, int b, int g, int r) {
        int a;
        int[] p;

        int lo = i - rad;
        if (lo < -1) {
            lo = -1;
        }
        int hi = i + rad;
        if (hi > netsize) {
            hi = netsize;
        }

        int j = i + 1;
        int k = i - 1;
        int m = 1;
        while ((j < hi) || (k > lo)) {
            a = radpower[m++];
            if (j < hi) {
                p = network[j++];
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias;
                    p[1] -= (a * (p[1] - g)) / alpharadbias;
                    p[2] -= (a * (p[2] - r)) / alpharadbias;
                } catch (Exception e) {
                } // prevents 1.3 miscompilation
            }
            if (k > lo) {
                p = network[k--];
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias;
                    p[1] -= (a * (p[1] - g)) / alpharadbias;
                    p[2] -= (a * (p[2] - r)) / alpharadbias;
                } catch (Exception e) {
                }
            }
        }
    }

    /* Move neuron i towards biased (b,g,r) by factor alpha
       ---------------------------------------------------- */
    /**
     * 按学习率把指定神经元向目标颜色移动。
     *
     * @param alpha 当前学习率（已放大）
     * @param i     待调整神经元的下标
     * @param b     目标颜色的第一个分量（已放大）
     * @param g     目标颜色的第二个分量（已放大）
     * @param r     目标颜色的第三个分量（已放大）
     */
    protected void altersingle(int alpha, int i, int b, int g, int r) {
        /* alter hit neuron */
        int[] n = network[i];
        n[0] -= (alpha * (n[0] - b)) / initalpha;
        n[1] -= (alpha * (n[1] - g)) / initalpha;
        n[2] -= (alpha * (n[2] - r)) / initalpha;
    }

    /* Search for biased BGR values
       ---------------------------- */
    /**
     * 竞争选取最匹配的神经元：同时统计距离最近与偏置距离最小的神经元，并更新所有神经元的频率与偏置。
     *
     * @param b 目标颜色的第一个分量（已放大）
     * @param g 目标颜色的第二个分量（已放大）
     * @param r 目标颜色的第三个分量（已放大）
     * @return 偏置距离最小的神经元下标，即本次学习的胜出者
     */
    protected int contest(int b, int g, int r) {
        /* finds closest neuron (min dist) and updates freq */
        /* finds best neuron (min dist-bias) and returns position */
        /* for frequently chosen neurons, freq[i] is high and bias[i] is negative */
        /* bias[i] = gamma*((1/netsize)-freq[i]) */

        int i;
        int dist;
        int a;
        int biasdist;
        int betafreq;
        int[] n;

        int bestd = ~(1 << 31);
        int bestbiasd = bestd;
        int bestpos = -1;
        int bestbiaspos = bestpos;

        for (i = 0; i < netsize; i++) {
            n = network[i];
            dist = n[0] - b;
            if (dist < 0) {
                dist = -dist;
            }
            a = n[1] - g;
            if (a < 0) {
                a = -a;
            }
            dist += a;
            a = n[2] - r;
            if (a < 0) {
                a = -a;
            }
            dist += a;
            if (dist < bestd) {
                bestd = dist;
                bestpos = i;
            }
            biasdist = dist - ((bias[i]) >> (intbiasshift - netbiasshift));
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist;
                bestbiaspos = i;
            }
            betafreq = (freq[i] >> betashift);
            freq[i] -= betafreq;
            bias[i] += (betafreq << gammashift);
        }
        freq[bestpos] += beta;
        bias[bestpos] -= betagamma;
        return (bestbiaspos);
    }
}
